package com.interview.coach2.reservation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유니크 제약을 건드릴 수 있는 INSERT만 별도 트랜잭션으로 떼어낸다.
 *
 * 제약 위반이 나면 그 트랜잭션은 rollback-only로 표시된다. 바깥 트랜잭션에서 그대로
 * 터뜨리면 예약 흐름 전체가 무효가 되고 깔끔한 409 대신 500이 나간다.
 * REQUIRES_NEW로 격리하면 바깥은 멀쩡한 채로 예외만 받아 처리할 수 있다.
 *
 * saveAndFlush여야 한다. save만 하면 INSERT가 바깥 커밋 시점까지 미뤄져
 * 격리한 의미가 없어진다.
 */
@Component
public class ReservationWriter {

	private final ReservationRepository reservations;
	private final CustomerRepository customers;

	public ReservationWriter(ReservationRepository reservations, CustomerRepository customers) {
		this.reservations = reservations;
		this.customers = customers;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Reservation insert(Reservation reservation) {
		return reservations.saveAndFlush(reservation);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Customer insertCustomer(Customer customer) {
		return customers.saveAndFlush(customer);
	}
}
