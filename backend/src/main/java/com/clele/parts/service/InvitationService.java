package com.clele.parts.service;

import com.clele.parts.config.AppProperties;
import com.clele.parts.dto.AcceptInvitationRequest;
import com.clele.parts.dto.EmailLookupDTO;
import com.clele.parts.dto.InvitationDTO;
import com.clele.parts.dto.InvitationRequest;
import com.clele.parts.dto.PublicInvitationDTO;
import com.clele.parts.model.AppUser;
import com.clele.parts.model.InvitationStatus;
import com.clele.parts.model.Organisation;
import com.clele.parts.model.OrganisationInvitation;
import com.clele.parts.model.Permissions;
import com.clele.parts.repository.AppUserRepository;
import com.clele.parts.repository.OrganisationInvitationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Invitations to join an organisation — how an Organisation Admin adds people.
 *
 * <p>The split from {@link UserService} and {@link AdminUserService} is the point of the feature:
 * an Organisation Admin can neither create an account nor attach an existing one, because both
 * reach past their organisation (an account is installation-wide, and attaching someone's existing
 * account decides for them). Inviting reaches only as far as sending a mail; the invitee makes the
 * decision, and the permissions the admin chose are applied only once they accept.
 *
 * <p>Answering an invitation is <b>unauthenticated</b> — the token is the only credential — so
 * every method on that path re-derives everything from the token and refuses anything that is not
 * still {@link OrganisationInvitation#isOpen() open}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganisationInvitationRepository invitationRepository;
    private final AppUserRepository userRepository;
    private final CurrentOrganisationService currentOrganisationService;
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final ChangesService changesService;
    private final AppProperties appProperties;

    // ---------------------------------------------------------------- admin side (ORG_ADMIN)

    /** Every invitation ever sent for the organisation in force, newest first. */
    public List<InvitationDTO> findAllForCurrentOrganisation() {
        Long organisationId = currentOrganisationService.currentId();
        return invitationRepository.findByOrganisationIdOrderByCreatedAtDesc(organisationId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Who is behind an email address, for the invite dialog. Returns a "does not exist" answer
     * rather than a 404: not having an account is a normal, expected outcome here.
     */
    public EmailLookupDTO lookup(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Long organisationId = currentOrganisationService.currentId();
        Optional<AppUser> user = userRepository.findByEmail(email);
        boolean invited = invitationRepository
                .findByEmailAndOrganisationIdAndStatus(email, organisationId, InvitationStatus.PENDING)
                .filter(OrganisationInvitation::isOpen)
                .isPresent();
        return EmailLookupDTO.builder()
                .email(email)
                .exists(user.isPresent())
                .fullName(user.map(AppUser::getFullName).orElse(null))
                .member(user.map(u -> isMember(u, organisationId)).orElse(false))
                .invited(invited)
                .build();
    }

    /**
     * Invite an address to the organisation in force. The invitation is created even if the mail
     * cannot be sent — the link is returned either way, so the admin can pass it on themselves.
     */
    @Transactional
    public InvitationDTO invite(InvitationRequest request) {
        String email = normalizeEmail(request.getEmail());
        Organisation organisation = currentOrganisationService.current();
        AppUser inviter = currentUserService.current();

        userRepository.findByEmail(email).ifPresent(existing -> {
            if (isMember(existing, organisation.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "That user is already a member of " + organisation.getName());
            }
        });
        invitationRepository
                .findByEmailAndOrganisationIdAndStatus(email, organisation.getId(),
                        InvitationStatus.PENDING)
                .filter(OrganisationInvitation::isOpen)
                .ifPresent(open -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "An invitation for " + email + " is already outstanding. "
                            + "Withdraw it first to send a new one.");
                });

        OrganisationInvitation invitation = OrganisationInvitation.builder()
                .token(newToken())
                .email(email)
                .organisation(organisation)
                .invitedBy(inviter)
                .status(InvitationStatus.PENDING)
                .permissions(sanitize(request.getPermissions()))
                .expiresAt(LocalDateTime.now()
                        .plusDays(appProperties.getMail().getInvitationExpiryDays()))
                .build();
        invitation = invitationRepository.save(invitation);

        String link = linkFor(invitation);
        boolean sent = mailService.sendInvitation(invitation, link);

        InvitationDTO dto = toDTO(invitation);
        dto.setMailSent(sent);
        dto.setLink(link);
        return dto;
    }

    /** Withdraw an outstanding invitation. Answered ones are history and stay as they are. */
    @Transactional
    public void revoke(Long id) {
        OrganisationInvitation invitation = requireInCurrentOrganisation(id);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That invitation has already been answered");
        }
        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setRespondedAt(LocalDateTime.now());
        invitationRepository.save(invitation);
    }

    // ------------------------------------------------------------- invitee side (public, token)

    /** What the accept/decline page shows. Unknown token ⇒ 404. */
    public PublicInvitationDTO findByToken(String token) {
        OrganisationInvitation invitation = requireToken(token);
        boolean newAccount = userRepository.findByEmail(invitation.getEmail()).isEmpty();
        return PublicInvitationDTO.builder()
                .email(invitation.getEmail())
                .organisationName(invitation.getOrganisation().getName())
                .invitedByName(inviterName(invitation))
                .permissions(new HashSet<>(invitation.getPermissions()))
                .status(invitation.getStatus().name())
                .expired(invitation.isExpired())
                .open(invitation.isOpen())
                .newAccount(newAccount)
                .build();
    }

    /**
     * Accept: join the organisation with the invited permissions, creating the account first if
     * this address has none.
     *
     * <p>For an <em>existing</em> account the request body is ignored entirely. Anyone holding the
     * token could otherwise rewrite that person's name, phone number and password — the token
     * proves control of the mailbox, which is enough to add a membership but nowhere near enough to
     * take over an account.
     */
    @Transactional
    public PublicInvitationDTO accept(String token, AcceptInvitationRequest request) {
        OrganisationInvitation invitation = requireOpen(token);
        Organisation organisation = invitation.getOrganisation();

        AppUser user = userRepository.findByEmail(invitation.getEmail())
                .orElseGet(() -> createAccount(invitation, request));

        if (!isMember(user, organisation.getId())) {
            user.getOrganisations().add(organisation);
        }
        // The permissions the admin chose when inviting; they replace whatever was held here
        // before, which for a new membership is nothing.
        user.setPermissionsIn(organisation.getId(), sanitize(invitation.getPermissions()));
        if (user.getLastOrganisation() == null) {
            user.setLastOrganisation(organisation);
        }
        userRepository.save(user);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setRespondedAt(LocalDateTime.now());
        invitationRepository.save(invitation);
        return findByToken(token);
    }

    /** Refuse. The account, if any, is untouched. */
    @Transactional
    public PublicInvitationDTO decline(String token) {
        OrganisationInvitation invitation = requireOpen(token);
        invitation.setStatus(InvitationStatus.DECLINED);
        invitation.setRespondedAt(LocalDateTime.now());
        invitationRepository.save(invitation);
        return findByToken(token);
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Create the account an invitation implies. Name, phone and password are all required: this is
     * the only moment they are asked for, and an account without a password cannot log in.
     */
    private AppUser createAccount(OrganisationInvitation invitation, AcceptInvitationRequest request) {
        String fullName = trimToNull(request == null ? null : request.getFullName());
        String phone = trimToNull(request == null ? null : request.getPhone());
        String password = request == null ? null : request.getPassword();
        if (fullName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your name is required");
        }
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your phone number is required");
        }
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A password is required");
        }
        return AppUser.builder()
                .email(invitation.getEmail())
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName)
                .phone(phone)
                .organisations(new HashSet<>())
                // A brand-new account has nothing to catch up on in the changelog.
                .lastReadChanges(changesService.getLatestDate())
                .build();
    }

    /**
     * The link mailed to the invitee. {@code app.base-url} wins when set; otherwise it is derived
     * from the request that is creating the invitation, which is right for a plain deployment and
     * wrong only behind a proxy that rewrites the host — hence the setting.
     */
    private String linkFor(OrganisationInvitation invitation) {
        String base = appProperties.getBaseUrl();
        if (base == null || base.isBlank()) {
            base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        }
        return base.replaceAll("/+$", "") + "/invite/" + invitation.getToken();
    }

    /** 256 bits of randomness, URL-safe — the sole credential on the accept link. */
    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private OrganisationInvitation requireToken(String token) {
        return invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This invitation link is not valid"));
    }

    private OrganisationInvitation requireOpen(String token) {
        OrganisationInvitation invitation = requireToken(token);
        if (!invitation.isOpen()) {
            String reason;
            if (invitation.isExpired()) {
                reason = "This invitation has expired. Ask for a new one.";
            } else if (invitation.getStatus() == InvitationStatus.REVOKED) {
                reason = "This invitation was withdrawn by an administrator";
            } else {
                reason = "This invitation has already been answered";
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
        }
        return invitation;
    }

    /** Another organisation's invitation does not exist as far as this one is concerned. */
    private OrganisationInvitation requireInCurrentOrganisation(Long id) {
        OrganisationInvitation invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Invitation not found: " + id));
        if (!invitation.getOrganisation().getId().equals(currentOrganisationService.currentId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found: " + id);
        }
        return invitation;
    }

    private boolean isMember(AppUser user, Long organisationId) {
        return user.getOrganisations().stream().anyMatch(o -> o.getId().equals(organisationId));
    }

    private String inviterName(OrganisationInvitation invitation) {
        AppUser inviter = invitation.getInvitedBy();
        if (inviter == null) return null;
        return inviter.getFullName() != null && !inviter.getFullName().isBlank()
                ? inviter.getFullName()
                : inviter.getEmail();
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Only per-organisation permissions can be invited with — GLOBAL_ADMIN is not an admin's to give. */
    private Set<String> sanitize(Set<String> permissions) {
        if (permissions == null) return new HashSet<>();
        return permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .filter(Permissions.PER_ORGANISATION::contains)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private InvitationDTO toDTO(OrganisationInvitation invitation) {
        return InvitationDTO.builder()
                .id(invitation.getId())
                .email(invitation.getEmail())
                .fullName(userRepository.findByEmail(invitation.getEmail())
                        .map(AppUser::getFullName).orElse(null))
                .permissions(new HashSet<>(invitation.getPermissions()))
                .status(invitation.getStatus().name())
                .expired(invitation.isExpired())
                .invitedByName(inviterName(invitation))
                .createdAt(invitation.getCreatedAt())
                .expiresAt(invitation.getExpiresAt())
                .respondedAt(invitation.getRespondedAt())
                .mailSent(true)
                .build();
    }
}
