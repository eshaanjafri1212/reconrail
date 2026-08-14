package in.reconrail.auth.service;

import in.reconrail.auth.dto.AuthResponse;
import in.reconrail.auth.dto.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
}
