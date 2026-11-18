package com.green.keyboard_project.repository;

import com.green.keyboard_project.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SocialAccountRepository extends JpaRepository<SocialAccount, Integer> {
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialAccount.SocialProvider provider, String providerUserId);
}
