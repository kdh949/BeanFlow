import { ArrowLeft, Check, Coffee, Minus, Plus, ShoppingBag } from "lucide-react";
import { useCallback, useState } from "react";
import { Link, useParams } from "react-router";
import type { components } from "../../api/schema";
import { ApiRequestError, unwrap } from "../../api/client";
import { customerApi } from "../../api/customerClient";
import { EmptyState, ErrorState, LoadingState } from "../../components/Ui";
import { PageTitle } from "../../components/Shells";
import { won } from "../../lib/format";
import { useResource } from "../shared/useResource";
import { Button, ButtonLink } from "../../design-system";
import type { CustomerStore } from "../discovery/useStore";
import { type CartLine, cart, cartItemCount, useCart } from "./cart";
import { FavoriteStoreButton } from "../customer/FavoriteStoresPage";

type Menu = components["schemas"]["Menu"];
type PickupSlot = components["schemas"]["PickupSlot"];

type Catalog = { store: CustomerStore; menus: Menu[]; slots: PickupSlot[] };

export function StoreDetailPage() {
  const { storeId = "" } = useParams();
  const cartState = useCart();

  // The store is read with the catalogue rather than taken from navigation state,
  // so opening this screen by URL or reloading it names the real store.
  const load = useCallback(async (): Promise<Catalog> => {
    const [storeResult, menuResult, slotResult] = await Promise.all([
      customerApi.GET("/stores/{storeId}", { params: { path: { storeId } } }),
      customerApi.GET("/stores/{storeId}/menus", { params: { path: { storeId } } }),
      customerApi.GET("/stores/{storeId}/pickup-slots", { params: { path: { storeId } } }),
    ]);
    return { store: unwrap(storeResult), menus: unwrap(menuResult).items, slots: unwrap(slotResult).items };
  }, [storeId]);
  const { state, reload } = useResource<Catalog>(load);

  if (state.status === "loading") return <LoadingState label="메뉴와 픽업 시간을 준비하는 중" />;
  // A store that is gone is a normal outcome of an old link, not a system fault,
  // and the server's own sentence is not written for a customer to read.
  if (state.status === "failed" && state.error instanceof ApiRequestError && state.error.status === 404) {
    return (
      <div className="customer-page">
        <Link className="back-link" to="/app/stores"><ArrowLeft size={17} /> 매장 찾기</Link>
        <EmptyState
          title="지금은 주문할 수 없는 매장이에요"
          description="주소가 바뀌었거나 더 이상 주문을 받지 않는 매장입니다. 다른 매장을 찾아보세요."
          action={<ButtonLink to="/app/stores">매장 찾기</ButtonLink>}
        />
      </div>
    );
  }
  if (state.status === "failed") return <ErrorState error={state.error} retry={reload} />;

  const { store, menus, slots } = state.value;
  const storeName = store.name;
  const openForOrders = slots.some((slot) => slot.remainingCapacity > 0);
  const itemCount = cartItemCount(cartState);

  return (
    <div className="customer-page catalog-page">
      <Link className="back-link" to="/app/stores"><ArrowLeft size={17} /> 매장 찾기</Link>
      <PageTitle
        eyebrow="ORDER"
        title={storeName}
        description="지금 판매 중인 메뉴와 픽업 시간만 보여드려요."
        action={(
          <div className="page-actions">
            <FavoriteStoreButton storeId={storeId} storeName={storeName} />
            <ButtonLink variant="secondary" to={`/app/coupons?storeId=${encodeURIComponent(storeId)}`}>쿠폰 보기</ButtonLink>
          </div>
        )}
      />

      {!openForOrders ? (
        <p className="inline-note" role="status">지금은 픽업 시간이 모두 마감됐어요. 잠시 뒤 다시 확인해 주세요.</p>
      ) : (
        <p className="inline-note" role="status">픽업 가능한 시간 {slots.filter((slot) => slot.remainingCapacity > 0).length}개가 열려 있어요.</p>
      )}

      <section className="menu-list">
        {menus.length === 0
          ? <EmptyState title="판매 중인 메뉴가 없어요" description="잠시 뒤 다시 확인해 주세요." />
          : menus.map((menu) => (
            <MenuRow key={menu.menuId} menu={menu} storeId={storeId} storeName={storeName} orderable={openForOrders} />
          ))}
      </section>

      {itemCount > 0 ? (
        <div className="cart-cta">
          <ButtonLink size="xl" block to="/app/cart">
            <ShoppingBag size={18} /> 장바구니 {itemCount}개 보기
          </ButtonLink>
        </div>
      ) : null}
    </div>
  );
}

function MenuRow({ menu, storeId, storeName, orderable }: { menu: Menu; storeId: string; storeName: string; orderable: boolean }) {
  const [open, setOpen] = useState(false);
  const [quantity, setQuantity] = useState(1);
  const [optionIds, setOptionIds] = useState<string[]>([]);
  const [conflict, setConflict] = useState<{ currentStoreName: string; line: CartLine } | null>(null);
  const [added, setAdded] = useState(false);

  const selectable = menu.available && orderable;
  const options = menu.options ?? [];
  const optionPrice = options
    .filter((option) => optionIds.includes(option.optionId))
    .reduce((total, option) => total + option.additionalPriceKrw, 0);
  const unitPriceKrw = menu.basePriceKrw + optionPrice;

  function toggleOption(optionId: string) {
    setOptionIds((current) => (current.includes(optionId) ? current.filter((value) => value !== optionId) : [...current, optionId]));
  }

  function buildLine(): CartLine {
    return {
      menuId: menu.menuId,
      optionIds,
      quantity,
      display: {
        menuName: menu.name,
        optionNames: options.filter((option) => optionIds.includes(option.optionId)).map((option) => option.name),
        unitPriceKrw,
      },
    };
  }

  function addToCart() {
    const line = buildLine();
    const result = cart.add({ storeId, storeName }, line);
    if (result.outcome === "other-store") {
      setConflict({ currentStoreName: result.currentStoreName, line });
      return;
    }
    setAdded(true);
    setOpen(false);
    setQuantity(1);
    setOptionIds([]);
  }

  return (
    <div className={`menu-card-group ${menu.available ? "" : "is-soldout"}`}>
      <button
        type="button"
        className={`menu-card ${open ? "is-selected" : ""}`}
        disabled={!selectable}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {menu.image ? (
          <img className="menu-thumbnail" src={menu.image.url} alt="" />
        ) : (
          <span className="menu-icon"><Coffee size={25} /></span>
        )}
        <span>
          <strong>{menu.name}</strong>
          <small>{won.format(menu.basePriceKrw)}{menu.available ? "" : " · 품절"}</small>
        </span>
        {open ? <Check size={19} /> : <Plus size={19} />}
      </button>

      {open ? (
        <div className="menu-config surface-card">
          {options.length ? (
            <fieldset className="option-group">
              <legend>옵션</legend>
              {options.map((option) => (
                <label key={option.optionId} className={option.available ? "" : "is-soldout"}>
                  <input
                    type="checkbox"
                    checked={optionIds.includes(option.optionId)}
                    disabled={!option.available}
                    onChange={() => toggleOption(option.optionId)}
                  />
                  <span>{option.name}{option.available ? "" : " · 품절"}</span>
                  <b>+{won.format(option.additionalPriceKrw)}</b>
                </label>
              ))}
            </fieldset>
          ) : null}
          <div className="selection-row">
            <span>수량</span>
            <span className="stepper">
              <button type="button" aria-label="수량 줄이기" onClick={() => setQuantity((value) => Math.max(1, value - 1))}><Minus size={16} /></button>
              <strong>{quantity}</strong>
              <button type="button" aria-label="수량 늘리기" onClick={() => setQuantity((value) => Math.min(20, value + 1))}><Plus size={16} /></button>
            </span>
          </div>
          <Button block onClick={addToCart}>
            {won.format(unitPriceKrw * quantity)} 담기
          </Button>
          <p className="form-footnote">금액과 재고는 주문할 때 매장 기준으로 다시 확인해요.</p>
        </div>
      ) : null}

      {added ? <p className="inline-note" role="status">장바구니에 담았어요.</p> : null}

      {conflict ? (
        <div className="surface-card cart-conflict" role="alertdialog" aria-label="다른 매장 장바구니">
          <strong>장바구니에 {conflict.currentStoreName} 주문이 담겨 있어요</strong>
          <p>한 번에 한 매장만 주문할 수 있어요. 기존 장바구니를 비우고 이 메뉴를 담을까요?</p>
          <div>
            <Button variant="ghost" onClick={() => setConflict(null)}>그대로 두기</Button>
            <Button
              onClick={() => {
                cart.replaceWith({ storeId, storeName }, conflict.line);
                setConflict(null);
                setAdded(true);
                setOpen(false);
              }}
            >
              비우고 담기
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
