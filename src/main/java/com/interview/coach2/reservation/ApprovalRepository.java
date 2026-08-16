package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

	Optional<Approval> findByToken(String token);

	Optional<Approval> findByBoothIdAndPhone(Long boothId, String phone);

	List<Approval> findByBoothIdOrderByNameAsc(Long boothId);

	long countByBoothId(Long boothId);
}
