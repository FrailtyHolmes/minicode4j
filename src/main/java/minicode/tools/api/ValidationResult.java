package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具入参校验的结果——「是否合法」+「规范化后的输入」+「错误清单」三合一。
 *
 * <p>为什么不只返回 boolean？因为校验和「规范化（normalization）」其实是同一动作的两面。
 * LLM 输出的 JSON 经常不规范（数字写成字符串 "42"、字段前后带空格、缺可选字段等），
 * 校验过程顺便把它们 trim、补默认值、转类型，把「洗干净」的 input 通过 {@link #normalizedInput}
 * 带回去给 {@link Tool#run}。这样下游业务代码就不用再 defensive coding，
 * 思路跟 Spring Validation 把校验后的对象返回给 controller 一致。
 *
 * <p>不变式（在 compact 构造器里强校验）：
 * <p>- {@code valid=true} 必须携带 {@code normalizedInput}，且 {@code errors} 为空；
 * <p>- {@code valid=false} 必须携带至少一条 {@code errors}，{@code normalizedInput} 为空。
 *
 * <p>不要直接 new；推荐用 {@link #valid(JsonNode)} / {@link #invalid(List)} 工厂方法。
 *
 * @param valid           校验是否通过
 * @param normalizedInput 通过时携带的规范化输入；失败时为 {@link Optional#empty()}
 * @param errors          失败时的错误清单；通过时为空列表（防御性 copy）
 */
public record ValidationResult(boolean valid, Optional<JsonNode> normalizedInput, List<String> errors) {
    /**
     * Compact 构造器：把 {@code errors} 防御性 copy 成不可变列表，并强校验语义不变式。
     *
     * @throws IllegalArgumentException 如果 {@code valid=true} 却带了 errors，
     *                                  或者 {@code valid=false} 却没有任何 error
     */
    public ValidationResult {
        normalizedInput = Objects.requireNonNull(normalizedInput, "normalizedInput");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("valid validation result cannot carry errors");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("invalid validation result requires errors");
        }
    }

    /**
     * 构造一个「校验通过」的结果。
     *
     * @param normalizedInput 规范化后的输入 JSON，不能为 {@code null}
     * @return 校验通过的 {@code ValidationResult}
     */
    public static ValidationResult valid(JsonNode normalizedInput) {
        return new ValidationResult(true, Optional.of(Objects.requireNonNull(normalizedInput, "normalizedInput")), List.of());
    }

    /**
     * 构造一个「校验失败」的结果。
     *
     * @param errors 至少包含一条错误信息的列表
     * @return 校验失败的 {@code ValidationResult}
     */
    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, Optional.empty(), errors);
    }
}
