package com.nomendi6.orgsec.dto;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * Data transfer object for user information.
 * This class contains minimal user data needed by the orgsec module,
 * abstracting away the underlying entity structure.
 */
public class UserData implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String login;
    private String email;
    private String firstName;
    private String lastName;
    private boolean activated;
    private Set<String> authorities = new HashSet<>();

    // Constructors

    public UserData() {}

    public UserData(String id, String login) {
        this.id = id;
        this.login = login;
    }

    public UserData(String id, String login, Set<String> authorities) {
        this.id = id;
        this.login = login;
        this.authorities = authorities != null ? authorities : new HashSet<>();
    }

    // Builder pattern for convenient construction

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private UserData userData = new UserData();

        public Builder id(String id) {
            userData.id = id;
            return this;
        }

        public Builder login(String login) {
            userData.login = login;
            return this;
        }

        public Builder email(String email) {
            userData.email = email;
            return this;
        }

        public Builder firstName(String firstName) {
            userData.firstName = firstName;
            return this;
        }

        public Builder lastName(String lastName) {
            userData.lastName = lastName;
            return this;
        }

        public Builder activated(boolean activated) {
            userData.activated = activated;
            return this;
        }

        public Builder authorities(Set<String> authorities) {
            userData.authorities = authorities != null ? new HashSet<>(authorities) : new HashSet<>();
            return this;
        }

        public Builder addAuthority(String authority) {
            if (userData.authorities == null) {
                userData.authorities = new HashSet<>();
            }
            userData.authorities.add(authority);
            return this;
        }

        public UserData build() {
            return userData;
        }
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public Set<String> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(Set<String> authorities) {
        this.authorities = authorities != null ? authorities : new HashSet<>();
    }

    /**
     * Check if the user has a specific authority.
     * @param authority the authority to check
     * @return true if the user has the authority, false otherwise
     */
    public boolean hasAuthority(String authority) {
        return authorities != null && authorities.contains(authority);
    }

    /**
     * Check if the user has admin authority.
     * @return true if the user has ROLE_ADMIN authority, false otherwise
     */
    public boolean isAdmin() {
        return hasAuthority("ROLE_ADMIN");
    }

    /**
     * Get the full name of the user.
     * @return the concatenated first and last name, or just login if names are not available
     */
    public String getFullName() {
        if (firstName != null && lastName != null) {
            return firstName + " " + lastName;
        } else if (firstName != null) {
            return firstName;
        } else if (lastName != null) {
            return lastName;
        } else {
            return login;
        }
    }

    @Override
    public String toString() {
        return (
            "UserData{" +
            "id='" +
            id +
            '\'' +
            ", login='" +
            login +
            '\'' +
            ", email='" +
            email +
            '\'' +
            ", activated=" +
            activated +
            ", authorities=" +
            authorities +
            '}'
        );
    }
}
