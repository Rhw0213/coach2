package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoothRepository extends JpaRepository<Booth, Long> {

	List<Booth> findByActiveTrueOrderByBoothNoAsc();

	Optional<Booth> findByStaffToken(String staffToken);

	Optional<Booth> findByApplyToken(String applyToken);
}
