package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VisitorRepository extends JpaRepository<Visitor, Long> {

	Optional<Visitor> findByPhone(String phone);

	Optional<Visitor> findByToken(String token);
}
