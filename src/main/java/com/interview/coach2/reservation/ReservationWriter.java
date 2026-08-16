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
	private final VisitorRepository visitors;

	public ReservationWriter(ReservationRepository reservations, VisitorRepository visitors) {
		this.reservations = reservations;
		this.visitors = visitors;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Reservation insert(Reservation reservation) {
		return reservations.saveAndFlush(reservation);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Visitor insertVisitor(Visitor visitor) {
		return visitors.saveAndFlush(visitor);
	}

	/**
	 * 이미 있는 사람의 소속을 신청서 값으로 맞춘다.
	 *
	 * 여기에도 트랜잭션이 필요하다 — book()에는 트랜잭션이 없어서(커넥션 데드락 때문에
	 * 일부러 뺐다) 더티체킹이 돌 자리가 없다. 값이 그대로면 JPA가 UPDATE를 내지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void refreshProfile(Long visitorId, String school, String major, String standing) {
		visitors.findById(visitorId)
			.ifPresent(v -> v.updateProfile(school, major, standing));
	}
}
