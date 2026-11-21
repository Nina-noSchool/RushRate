package com.hmdp.ratelimit;

import com.hmdp.utils.UserHolder;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;

/**
 * 限流 key 的 SpEL 解析器（2026-11-21 批次）。
 *
 * <p>内置变量：{@code #ip}（客户端 IP，切面从 RequestContextHolder 提取）、
 * {@code #userId}（登录用户，未登录时为 null）；方法参数可直接按参数名引用
 * （{@code #voucherId} / {@code #phone} 等，依赖编译期 -parameters，Spring Boot 父 POM 默认开启）。</p>
 */
public class RateLimitKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 解析限流 key。解析失败返回空串（调用方按"解析失败不拦截"处理，fail-open 与
     * 布隆过滤器同一哲学：限流组件自身异常不阻断业务主链路）。
     */
    public String resolve(String expression, Method method, Object[] args, String clientIp) {
        try {
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    null, method, args, nameDiscoverer);
            context.setVariable("ip", clientIp);
            context.setVariable("userId", currentUserIdOrNull());
            String value = parser.parseExpression(expression).getValue(context, String.class);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private Long currentUserIdOrNull() {
        try {
            return UserHolder.getUser().getId();
        } catch (Exception e) {
            return null;
        }
    }
}
