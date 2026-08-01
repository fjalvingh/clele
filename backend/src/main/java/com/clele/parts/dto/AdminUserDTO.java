package com.clele.parts.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * A user account seen from the installation-wide "All Users" screen: the account itself plus
 * <em>every</em> organisation it belongs to and what it may do in each.
 *
 * <p>Deliberately not {@link UserDTO}, which reports permissions for one organisation — the one in
 * force — because that is all an Organisation Admin may see. This DTO is the Global Administrator's
 * view and crosses organisation boundaries, so it is only ever produced by {@code AdminUserService}.
 */
@Data
@Builder
public class AdminUserDTO {
    private Long id;
    private String email;
    private String fullName;
    private String phone;
    /** Permissions in force everywhere, independent of organisation (GLOBAL_ADMIN). */
    private Set<String> globalPermissions;
    /** One entry per organisation the user belongs to, with their permissions in it. */
    private List<UserMembershipDTO> memberships;
}
