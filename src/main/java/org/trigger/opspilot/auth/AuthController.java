package org.trigger.opspilot.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.JwtService;
import org.trigger.opspilot.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthSessionService sessions;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService, AuthSessionService sessions) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        if (!PasswordPolicy.validBcryptInput(request.password())) throw new BadCredentialsException("Invalid credentials");
        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return ApiResponse.ok(new LoginResponse(
                jwtService.createToken(principal), "Bearer", jwtService.expiresInSeconds(), UserView.from(principal)));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(UserView.from(principal));
    }

    @PostMapping("/logout-all")
    public ApiResponse<SessionChangeResponse> logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        sessions.revokeAll(principal);
        return ApiResponse.ok(new SessionChangeResponse(true, "ALL_ISSUED_SESSIONS"));
    }

    @PostMapping("/password")
    public ApiResponse<SessionChangeResponse> password(@AuthenticationPrincipal UserPrincipal principal,
                                                      @Valid @RequestBody PasswordChangeRequest request) {
        sessions.changePassword(principal, request.currentPassword(), request.newPassword());
        return ApiResponse.ok(new SessionChangeResponse(true, "ALL_ISSUED_SESSIONS"));
    }

    public record PasswordChangeRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(max = 72) String newPassword) { }
    public record SessionChangeResponse(boolean reauthenticationRequired, String scope) { }

    public record LoginRequest(@NotBlank String username, @NotBlank @Size(max = 72) String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    public record UserView(Long id, String username, String displayName, String roleCode) {
        static UserView from(UserPrincipal principal) {
            return new UserView(principal.id(), principal.username(), principal.displayName(), principal.roleCode());
        }
    }
}
