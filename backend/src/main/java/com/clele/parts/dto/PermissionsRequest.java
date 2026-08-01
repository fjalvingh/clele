package com.clele.parts.dto;

import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/** Replace a user's permissions within one named organisation (All Users screen). */
@Data
public class PermissionsRequest {
    private Set<String> permissions = new HashSet<>();
}
