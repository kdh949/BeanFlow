package io.github.kdh949.beanflow.shared.internal

import org.springframework.core.io.ClassPathResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

private const val TARGET_REF_PREFIX = "./beanflow-v1.yaml#/"

/**
 * beanflow-v1-runtime.yaml의 "./beanflow-v1.yaml#/..." 상대 $ref는 브라우저에서 두 개의
 * 별도 HTTP 리소스를 오가며 resolve해야 하는데, Scalar의 클라이언트 측 bundler가 이를
 * 안정적으로 처리하지 못한다(콘솔에 "Failed to resolve external reference"). 대신 서버에서
 * 두 파일을 하나로 병합하고 cross-file $ref를 local `#/...` 참조로 재작성해 단일 문서로 서빙한다.
 */
@RestController
internal class DocumentationSpecController {
    private val bundledSpecYaml: String by lazy { bundle() }

    @GetMapping("/docs/spec/openapi.yaml", produces = ["application/yaml"])
    fun openApiSpec(): String = bundledSpecYaml

    private fun bundle(): String {
        val yaml = Yaml()
        val runtimeDoc = yaml.load<MutableMap<String, Any?>>(ClassPathResource("openapi/beanflow-v1-runtime.yaml").inputStream)
        val targetDoc = yaml.load<MutableMap<String, Any?>>(ClassPathResource("openapi/beanflow-v1.yaml").inputStream)

        val merged = LinkedHashMap<String, Any?>(runtimeDoc)
        merged["paths"] = mergePaths(targetDoc.asStringMap("paths"), runtimeDoc.asStringMap("paths"))
        merged["components"] = mergeComponents(targetDoc.asStringMap("components"), runtimeDoc.asStringMap("components"))
        rewriteCrossFileRefs(merged)

        val dumperOptions =
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
            }
        return Yaml(dumperOptions).dump(merged)
    }

    /**
     * runtime.yaml의 각 path item은 (a) target 전체를 그대로 위임하는 단일-key
     * `{$ref: "./beanflow-v1.yaml#/paths/..."}` 이거나 (b) runtime이 직접 정의한 operation
     * 객체다. runtime.yaml은 "Controller mapping과 계약 테스트가 함께 존재하는" 것만 담으므로
     * 병합 결과의 경로 key 집합은 반드시 runtime.paths와 같아야 한다(target에만 있는 draft
     * 경로를 노출하면 안 된다). (a)는 target의 실제 정의로 치환해야 자기 자신을 가리키는
     * dangling $ref가 되지 않는다. (b)는 runtime 쪽 정의를 그대로 쓴다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mergePaths(
        targetPaths: Map<String, Any?>,
        runtimePaths: Map<String, Any?>,
    ): LinkedHashMap<String, Any?> {
        val merged = LinkedHashMap<String, Any?>()
        runtimePaths.forEach { (path, item) ->
            val isWholePathRef = item is Map<*, *> && item.size == 1 && item.containsKey("\$ref")
            merged[path] = if (isWholePathRef) targetPaths[path] else item
        }
        return merged
    }

    /**
     * runtime.yaml은 자신만의 legacy `components`(예: RuntimeStoreOrderResult)를 갖는다.
     * target 컴포넌트를 기본으로 삼고 runtime 쪽 하위 section(schemas, parameters 등)을
     * 각각 얹어서 어느 한쪽도 유실되지 않게 한다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mergeComponents(
        targetComponents: Map<String, Any?>,
        runtimeComponents: Map<String, Any?>,
    ): LinkedHashMap<String, Any?> {
        val merged = LinkedHashMap<String, Any?>(targetComponents)
        runtimeComponents.forEach { (sectionName, runtimeSection) ->
            val targetSection = merged[sectionName] as? Map<String, Any?>
            merged[sectionName] =
                if (targetSection != null && runtimeSection is Map<*, *>) {
                    LinkedHashMap(targetSection).apply { putAll(runtimeSection as Map<String, Any?>) }
                } else {
                    runtimeSection
                }
        }
        return merged
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.asStringMap(key: String): Map<String, Any?> = (get(key) as? Map<String, Any?>).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun rewriteCrossFileRefs(node: Any?) {
        when (node) {
            is MutableMap<*, *> -> {
                val map = node as MutableMap<String, Any?>
                val ref = map["\$ref"]
                if (ref is String && ref.startsWith(TARGET_REF_PREFIX)) {
                    map["\$ref"] = "#/" + ref.removePrefix(TARGET_REF_PREFIX)
                }
                map.values.forEach { rewriteCrossFileRefs(it) }
            }

            is MutableList<*> -> {
                node.forEach { rewriteCrossFileRefs(it) }
            }
        }
    }
}
