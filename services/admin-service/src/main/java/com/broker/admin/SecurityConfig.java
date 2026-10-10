package com.broker.admin;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** One login for the whole console. No password, no start: this page can change log levels and stop jobs. */
@Configuration
class SecurityConfig {
    private final AdminServerProperties admin;

    SecurityConfig(AdminServerProperties admin) { this.admin = admin; }

    @Bean
    PasswordEncoder encoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService users(@Value("${admin.ui.user:admin}") String user, @Value("${admin.ui.password:}") String password, PasswordEncoder enc) {
        if (password.length() < 12) throw new IllegalStateException("ADMIN_UI_PASSWORD must be set (at least 12 characters): this console can change log levels and stop jobs.");
        return new InMemoryUserDetailsManager(User.withUsername(user).password(enc.encode(password)).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        String ctx = admin.getContextPath();
        var success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setTargetUrlParameter("redirectTo");
        success.setDefaultTargetUrl(ctx + "/");
        var csrf = new CsrfTokenRequestAttributeHandler();
        csrf.setCsrfRequestAttributeName(null);
        http.authorizeHttpRequests(a -> a.requestMatchers(ctx + "/assets/**", ctx + "/login", "/actuator/health/**").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.loginPage(ctx + "/login").successHandler(success))
            .logout(l -> l.logoutUrl(ctx + "/logout"))
            .httpBasic(Customizer.withDefaults())                                    // the services register themselves with Basic credentials
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrf)
                .ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, ctx + "/instances"),
                                         PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, ctx + "/instances/*"), PathPatternRequestMatcher.withDefaults().matcher("/actuator/**")));
        return http.build();
    }
}
