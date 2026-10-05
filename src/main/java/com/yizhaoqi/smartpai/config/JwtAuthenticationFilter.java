package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.service.CustomUserDetailsService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Authenticate the initial request only; MVC manages the already-authorized async response. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired private JwtUtils jwtUtils;
    @Autowired private CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                String username = null;
                String refreshed = null;
                if (jwtUtils.validateToken(token)) {
                    if (jwtUtils.shouldRefreshToken(token)) {
                        refreshed = jwtUtils.refreshToken(token);
                        // A failed cache check during refresh must not restore authentication.
                        if (refreshed != null) username = jwtUtils.extractUsernameFromToken(refreshed);
                    } else {
                        username = jwtUtils.extractUsernameFromToken(token);
                    }
                } else if (jwtUtils.canRefreshExpiredToken(token)) {
                    refreshed = jwtUtils.refreshToken(token);
                    if (refreshed != null) username = jwtUtils.extractUsernameFromToken(refreshed);
                }
                if (username != null && !username.isBlank()) {
                    UserDetails details = userDetailsService.loadUserByUsername(username);
                    var authentication = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    if (refreshed != null) response.setHeader("New-Token", refreshed);
                }
            } catch (RuntimeException error) {
                SecurityContextHolder.clearContext();
                logger.warn("JWT authentication rejected");
            }
        }
        // Downstream HTTP/async exceptions belong to MVC and must never be swallowed here.
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }
}
