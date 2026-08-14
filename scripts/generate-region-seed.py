#!/usr/bin/env python3
"""행정안전부 법정동 코드 전체자료를 merchant_region 시드 SQL로 변환한다.

MD-2026-016대로 원본 파일은 저장소에 넣지 않고 생성 결과인 시드 SQL만 커밋한다.
원본은 배포 시점마다 인코딩·구분자·행 수가 달라지므로 checksum을 고정해
같은 입력에서 같은 출력이 나오는 것을 보장한다.

사용법:

    python3 scripts/generate-region-seed.py \
        --output src/main/resources/db/migration/V58__seed_merchant_region.sql

    # 원본을 이미 받아 둔 경우(망 분리 환경 등)
    python3 scripts/generate-region-seed.py --source-zip ./법정동코드.zip --output ...

    # 행정구역 개편으로 원본이 갱신됐을 때: 새 checksum을 확인만 하고 끝낸다
    python3 scripts/generate-region-seed.py --print-checksum

출력은 정렬된 ``INSERT ... ON CONFLICT DO NOTHING``이라 재실행해도 행 수가 같다.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

# 행정안전부 행정표준코드관리시스템의 "법정동코드 전체자료" 다운로드 지점.
# 화면에서는 POST 폼으로 호출하며 codeseId가 자료 종류를 고른다.
SOURCE_URL = "https://www.code.go.kr/etc/codeFullDown.do"
SOURCE_FORM_DATA = {"codeseId": "법정동코드"}
SOURCE_REFERER = "https://www.code.go.kr/stdcode/regCodeL.do"

# 2026-07-08 배포본. ZIP 안의 텍스트 파일 내용을 기준으로 고정한다.
# ZIP 자체가 아니라 내용을 고정해야 압축 메타데이터가 달라져도 같은 자료임을 알 수 있다.
EXPECTED_SOURCE_SHA256 = "8cfd829c797270b56243a46e9f1e4e95377c2135153c36909021a35a1e32966a"
EXPECTED_ROW_COUNT = 20560

SOURCE_ENCODING = "cp949"
ACTIVE_MARKER = "존재"

TABLE = "merchant_region"


def download_source() -> bytes:
    body = urllib.parse.urlencode(SOURCE_FORM_DATA, encoding=SOURCE_ENCODING).encode("ascii")
    request = urllib.request.Request(
        SOURCE_URL,
        data=body,
        headers={
            "Referer": SOURCE_REFERER,
            "Content-Type": "application/x-www-form-urlencoded",
        },
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = response.read()
    if not payload.startswith(b"PK"):
        raise SystemExit(
            "다운로드 결과가 ZIP이 아니다. 원본 사이트가 응답 형식을 바꿨을 수 있으므로\n"
            "브라우저로 내려받은 뒤 --source-zip으로 전달한다."
        )
    return payload


def read_source_text(archive_bytes: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(archive_bytes), metadata_encoding=SOURCE_ENCODING) as archive:
        entries = archive.infolist()
        if len(entries) != 1:
            raise SystemExit(f"ZIP 안의 파일이 1개가 아니다: {[e.filename for e in entries]}")
        raw = archive.read(entries[0])
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_SOURCE_SHA256:
        raise SystemExit(
            "원본 checksum이 고정값과 다르다. 자료가 갱신됐다면 결과를 눈으로 확인한 뒤\n"
            f"EXPECTED_SOURCE_SHA256과 EXPECTED_ROW_COUNT를 갱신한다.\n"
            f"  기대: {EXPECTED_SOURCE_SHA256}\n"
            f"  실제: {digest}"
        )
    return raw.decode(SOURCE_ENCODING)


def parse_active_rows(text: str) -> dict[str, str]:
    """폐지되지 않은 법정동만 코드 -> 법정동명으로 모은다.

    원본에는 이름 끝에 공백이 붙은 행이 있어(예: "경기도 부천시 원미구 ")
    상위 이름으로 접두사를 잘라 낼 때 어긋난다. 읽는 즉시 잘라 낸다.
    """
    active: dict[str, str] = {}
    lines = [line for line in text.replace("\r\n", "\n").split("\n") if line.strip()]
    for line in lines[1:]:  # 첫 줄은 헤더(법정동코드/법정동명/폐지여부)
        fields = line.split("\t")
        if len(fields) < 3:
            raise SystemExit(f"열이 3개 미만인 행이 있다: {line!r}")
        code, name, status = fields[0].strip(), fields[1].strip(), fields[2].strip()
        if status != ACTIVE_MARKER:
            continue
        if len(code) != 10 or not code.isdigit():
            raise SystemExit(f"법정동 코드가 10자리 숫자가 아니다: {code!r}")
        if code in active:
            raise SystemExit(f"법정동 코드가 중복이다: {code}")
        active[code] = name
    return active


def sido_root_codes(active: dict[str, str]) -> dict[str, str]:
    """시도 2자리별 최상위 행 코드.

    보통은 ``<시도>00000000``이지만 세종특별자치시에는 그 행이 없고
    시군구 자리를 쓴 ``3611000000``이 최상위다. 그래서 접두사별 최소 코드를 쓴다.
    """
    roots: dict[str, str] = {}
    for code in active:
        prefix = code[:2]
        if prefix not in roots or code < roots[prefix]:
            roots[prefix] = code
    return roots


def strip_parent(parent_name: str, full_name: str, code: str) -> str:
    """상위 행정구역 이름을 접두사로 잘라 남은 이름만 돌려준다.

    "경기도 부천시 원미구"처럼 시군구가 두 단어인 경우가 있어 공백 개수로 자를 수 없다.
    코드 계층으로 상위 행을 찾은 뒤 그 이름을 접두사로 잘라야 정확하다.
    """
    if full_name == parent_name:
        return ""
    prefix = parent_name + " "
    if not full_name.startswith(prefix):
        raise SystemExit(f"상위 이름이 접두사가 아니다: code={code} parent={parent_name!r} full={full_name!r}")
    return full_name[len(prefix) :]


def build_regions(active: dict[str, str]) -> list[tuple[str, str, str, str, str, str]]:
    roots = sido_root_codes(active)
    regions: list[tuple[str, str, str, str, str, str]] = []
    for code in sorted(active):
        full_name = active[code]
        root_code = roots[code[:2]]
        sido = active[root_code]

        sigungu = ""
        sigungu_code = code[:5] + "00000"
        if code[2:5] != "000":
            if sigungu_code not in active:
                raise SystemExit(f"시군구 상위 행이 없다: {code}")
            sigungu = strip_parent(sido, active[sigungu_code], code)

        eupmyeondong = ""
        eupmyeondong_code = code[:8] + "00"
        if code[5:8] != "000":
            parent_code = sigungu_code if code[2:5] != "000" else root_code
            if eupmyeondong_code not in active:
                raise SystemExit(f"읍면동 상위 행이 없다: {code}")
            eupmyeondong = strip_parent(active[parent_code], active[eupmyeondong_code], code)

        # 리 행의 eupmyeondong은 위에서 상위 읍·면 이름으로 채워진다. 리 이름으로 덮어쓰지 않는다.
        # 덮어쓰면 리에 있는 매장이 읍·면 이름으로 검색되지 않아 검색 범위가 넓어지지 않고
        # 이동만 한다(ADR-112 리 Amendment R1).
        ri = ""
        if code[8:10] != "00":
            if code[5:8] == "000":
                raise SystemExit(f"읍면동 계층 없이 리 코드가 있다: {code}")
            ri = strip_parent(active[eupmyeondong_code], full_name, code)

        for label, value, limit in (
            ("sido", sido, 40),
            ("sigungu", sigungu, 40),
            ("eupmyeondong", eupmyeondong, 40),
            ("ri", ri, 40),
            ("full_name", full_name, 120),
        ):
            if len(value) > limit:
                raise SystemExit(f"{label}가 varchar({limit})를 넘는다: code={code} value={value!r}")
        if not sido:
            raise SystemExit(f"시도 이름이 비어 있다: {code}")
        if not full_name:
            raise SystemExit(f"법정동명이 비어 있다: {code}")

        regions.append((code, sido, sigungu, eupmyeondong, ri, full_name))
    return regions


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def render_sql(regions: list[tuple[str, str, str, str, str, str]]) -> str:
    ri_rows = sum(1 for region in regions if region[4])
    out = io.StringIO()
    out.write("-- Generated by scripts/generate-region-seed.py. Do not edit by hand.\n")
    out.write(f"-- Source: {SOURCE_URL} ({SOURCE_FORM_DATA['codeseId']})\n")
    out.write(f"-- Source SHA-256: {EXPECTED_SOURCE_SHA256}\n")
    out.write(f"-- Active rows: {len(regions)} (of which {ri_rows} are ri level)\n")
    out.write("--\n")
    out.write("-- 폐지되지 않은 법정동만 담는다. 시도/시군구/읍면동/리는 법정동 코드 계층으로\n")
    out.write("-- 상위 행 이름을 잘라 낸 값이고 full_name은 원본 법정동명 그대로다.\n")
    out.write("-- 리 행의 eupmyeondong은 상위 읍·면 이름을 그대로 유지한다. 리에 있는 매장이\n")
    out.write("-- 읍·면 이름과 리 이름 양쪽으로 검색되게 하기 위함이다.\n")
    out.write("-- ON CONFLICT DO NOTHING이라 재실행해도 행 수가 변하지 않는다.\n\n")
    out.write(f"INSERT INTO {TABLE} (code, sido, sigungu, eupmyeondong, ri, full_name) VALUES\n")
    rendered = [
        f"    ({quote(code)}, {quote(sido)}, {quote(sigungu)}, "
        f"{quote(eupmyeondong)}, {quote(ri)}, {quote(full_name)})"
        for code, sido, sigungu, eupmyeondong, ri, full_name in regions
    ]
    out.write(",\n".join(rendered))
    out.write("\nON CONFLICT (code) DO NOTHING;\n")
    return out.getvalue()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source-zip", type=Path, help="이미 받아 둔 원본 ZIP 경로. 없으면 직접 내려받는다")
    parser.add_argument("--output", type=Path, help="생성할 시드 SQL 경로")
    parser.add_argument(
        "--print-checksum",
        action="store_true",
        help="원본의 현재 checksum과 행 수만 출력하고 끝낸다",
    )
    args = parser.parse_args()

    archive_bytes = args.source_zip.read_bytes() if args.source_zip else download_source()

    if args.print_checksum:
        with zipfile.ZipFile(io.BytesIO(archive_bytes), metadata_encoding=SOURCE_ENCODING) as archive:
            raw = archive.read(archive.infolist()[0])
        text = raw.decode(SOURCE_ENCODING)
        active = parse_active_rows(text)
        print(f"sha256={hashlib.sha256(raw).hexdigest()}")
        print(f"active_rows={len(active)}")
        return 0

    if args.output is None:
        parser.error("--output 또는 --print-checksum 중 하나가 필요하다")

    text = read_source_text(archive_bytes)
    active = parse_active_rows(text)
    if len(active) != EXPECTED_ROW_COUNT:
        raise SystemExit(f"폐지되지 않은 행 수가 고정값과 다르다: 기대 {EXPECTED_ROW_COUNT}, 실제 {len(active)}")

    regions = build_regions(active)
    args.output.write_text(render_sql(regions), encoding="utf-8")
    print(f"{args.output} 생성 완료: {len(regions)}행")
    return 0


if __name__ == "__main__":
    sys.exit(main())
