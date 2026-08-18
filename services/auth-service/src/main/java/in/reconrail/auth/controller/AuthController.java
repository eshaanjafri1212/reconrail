package in.reconrail.auth.controller;

import in.reconrail.auth.config.JwtProperties;
import in.reconrail.auth.dto.*;
import in.reconrail.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request){
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginApiResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest){
        String userAgent = httpRequest.getHeader("User-Agent");
        String clientIp = resolveClientIp(httpRequest);

        LoginResponse result = authService.login(request,userAgent,clientIp);

        ResponseCookie cookie = ResponseCookie.from("refresh_token",result.refreshToken())
                .httpOnly(true)//xss defence
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(jwtProperties.refreshTokenTtl())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,cookie.toString())
                .body(new LoginApiResponse(
                        result.accessToken(),
                        result.userId(),
                        result.tenantSlug(),
                        result.role()
                ));
    }

    private String resolveClientIp(HttpServletRequest request){
        String cfIp = request.getHeader("CF-Connecting-IP");
        if(cfIp != null && !cfIp.isBlank()){
            return cfIp;
        }
        String forwarded = request.getHeader("X-Forwarded-For");//for audit purpose to keep a track of who connected to me
        if(forwarded != null && !forwarded.isBlank()){
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
