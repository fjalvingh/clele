package com.clele.parts.dto;

import lombok.*;

/**
 * Answer to "who is this address?", shown next to the email field on the invite dialog so the admin
 * can see they are inviting the person they meant. Deliberately minimal: it is readable by any
 * Organisation Admin for an arbitrary address, so it reveals only the display name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLookupDTO {

    private String email;
    /** Whether an account already exists for this address. */
    private boolean exists;
    /** Its full name, when there is one. */
    private String fullName;
    /** Whether that account is already a member of the current organisation. */
    private boolean member;
    /** Whether an invitation to this organisation is already open for this address. */
    private boolean invited;
}
