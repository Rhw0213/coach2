package com.interview.coach2.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoothRepository extends JpaRepository<Booth, Long> {

	List<Booth> findByActiveTrueOrderByBoothNoAsc();
}
