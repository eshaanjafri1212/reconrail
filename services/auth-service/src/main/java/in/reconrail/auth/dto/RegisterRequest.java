package in.reconrail.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 200) String companyName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 10, max = 100) String password,
        @Size(max = 200) String fullName
) {}