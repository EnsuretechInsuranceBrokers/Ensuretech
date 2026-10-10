package com.broker.sheetgen.querylog;

import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Wires the query log in: wraps every DataSource so each statement is timed, and notes which controller method each request ran. Only when ops.querylog.enabled=true. */
@Configuration
@ConditionalOnProperty(name = "ops.querylog.enabled", havingValue = "true")
class QueryLogConfig implements WebMvcConfigurer {
    @Bean
    static BeanPostProcessor queryLogDataSources() {
        QueryExecutionListener listener = new QueryExecutionListener() {
            @Override public void beforeQuery(ExecutionInfo info, java.util.List<QueryInfo> queries) {}
            @Override public void afterQuery(ExecutionInfo info, java.util.List<QueryInfo> queries) {
                double each = info.getElapsedTime() / (double) Math.max(1, queries.size());
                for (QueryInfo q : queries) QueryLog.INSTANCE.query(q.getQuery(), each, info.isSuccess(), MDC.get("correlationId"));
            }
        };
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) return ProxyDataSourceBuilder.create(ds).name("querylog").listener(listener).build();
                return bean;
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (handler instanceof HandlerMethod hm && hm.getBeanType() != QueryLogController.class)
                    QueryLog.INSTANCE.begin(MDC.get("correlationId"), req.getMethod(), req.getRequestURI(), hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName(), who());
                return true;
            }
            @Override public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                Object raised = ex != null ? ex : req.getAttribute("jakarta.servlet.error.exception");
                QueryLog.INSTANCE.end(res.getStatus(), raised == null ? null : raised.getClass().getSimpleName() + ": " + String.valueOf(((Throwable) raised).getMessage()).replaceAll("\\s+", " "));
            }
        }).excludePathPatterns("/actuator/**");
    }

    /** The caller, from the token Spring Security already verified: user id, role, broker and employer, and who opened it if it is a break-glass token. */
    private static QueryLog.Who who() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt)) return null;
        String user = jwt.getClaimAsString("user_id");
        if (user == null) user = jwt.getSubject();
        return new QueryLog.Who(user, jwt.getClaimAsString("role"), number(jwt.getClaim("broker_id")), number(jwt.getClaim("employer_id")),
                Boolean.TRUE.equals(jwt.getClaim("break_glass")) ? jwt.getClaimAsString("bg_by") : null);
    }

    private static Long number(Object o) { return o instanceof Number n ? Long.valueOf(n.longValue()) : null; }
}
