package com.smartagri.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlertExplanationRepository extends JpaRepository<AlertExplanation, Long> {

    Optional<AlertExplanation> findByCid(String cid);
}
