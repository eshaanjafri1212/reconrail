package in.reconrail.auth.service;

import in.reconrail.auth.dto.AuthResponse;
import in.reconrail.auth.dto.LoginRequest;
import in.reconrail.auth.dto.LoginResponse;
import in.reconrail.auth.dto.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    LoginResponse login(LoginRequest request, String userAgent, String ipAddress);
}
