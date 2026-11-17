package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.UserRole;
import com.company.incidentjmix.test_support.AuthenticatedAsAdmin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class AppUserServiceTest {

    @Autowired
    private AppUserService appUserService;

    @Test
    void listReturnsUsersSeededByLiquibaseSortedById() {
        List<AppUser> users = appUserService.list();

        assertThat(users).extracting(AppUser::getId).containsExactly(1, 2, 3, 4);
        assertThat(users.get(0).getName()).isEqualTo("Иванов И.И.");
        assertThat(users.get(0).getRole()).isEqualTo(UserRole.ENGINEER);
        assertThat(users.get(3).getName()).isEqualTo("Кузнецова М.М.");
        assertThat(users.get(3).getRole()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    void getByIdReturnsExistingUserAndNullForUnknown() {
        AppUser user = appUserService.getById(2);
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo("Петров П.П.");

        assertThat(appUserService.getById(999)).isNull();
    }

    @Test
    void resolveActorAcceptsKnownId() {
        AppUser actor = appUserService.resolveActor("1", true);
        assertThat(actor.getId()).isEqualTo(1);

        assertThat(appUserService.resolveActor("4", false).getRole()).isEqualTo(UserRole.MANAGER);
    }

    @Test
    void resolveActorRejectsMissingHeaderWhenRequired() {
        assertThatThrownBy(() -> appUserService.resolveActor(null, true))
                .isInstanceOf(UnknownUserException.class);
        assertThatThrownBy(() -> appUserService.resolveActor("  ", true))
                .isInstanceOf(UnknownUserException.class);
    }

    @Test
    void resolveActorAllowsMissingHeaderWhenOptional() {
        assertThat(appUserService.resolveActor(null, false)).isNull();
        assertThat(appUserService.resolveActor("", false)).isNull();
    }

    @Test
    void resolveActorRejectsNonNumericAndUnknownIds() {
        assertThatThrownBy(() -> appUserService.resolveActor("abc", true))
                .isInstanceOf(UnknownUserException.class);
        assertThatThrownBy(() -> appUserService.resolveActor("999", true))
                .isInstanceOf(UnknownUserException.class);
        assertThatThrownBy(() -> appUserService.resolveActor("999", false))
                .isInstanceOf(UnknownUserException.class);
    }
}
