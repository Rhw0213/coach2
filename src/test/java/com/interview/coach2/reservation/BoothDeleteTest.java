package com.interview.coach2.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 부스 삭제는 되돌릴 수 없다. 무엇이 함께 사라지는지, 무엇이 살아남아야 하는지를 못박는다.
 */
@SpringBootTest
class BoothDeleteTest {

	@Autowired AdminController admin;
	@Autowired ReservationService service;
	@Autowired BoothRepository booths;
	@Autowired ReservationRepository reservations;
	@Autowired VisitorRepository visitors;
	@Autowired ApprovalRepository approvals;

	private Booth booth;
	private Instant slot;

	@BeforeEach
	void setUp() {
		reservations.deleteAll();
		visitors.deleteAll();
		approvals.deleteAll();
		booths.deleteAll();

		booth = booths.save(new Booth("동해기업", "A-12", null, Slots.today().plusDays(7),
			LocalTime.of(10, 0), LocalTime.of(17, 0), 30, 5));
		slot = Slots.forBooth(booth).get(0);
	}

	/** 한 번에 지우게 두면 오타 하나로 예약이 잡힌 부스가 통째로 사라진다. */
	@Test
	void 운영_중인_부스는_지워지지_않는다() {
		assertThatThrownBy(() -> admin.deleteBooth(booth.getId()))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT));

		assertThat(booths.findById(booth.getId())).isPresent();
	}

	@Test
	void 내린_부스는_예약과_합격자_명단까지_함께_사라진다() {
		service.book(booth.getId(), slot, "홍길동", "01012345678");
		approvals.save(new Approval(booth.getId(), "김철수", "01033334444"));
		admin.setActive(booth.getId(), false);

		AdminController.DeletedBooth deleted = admin.deleteBooth(booth.getId());

		assertThat(deleted.companyName()).isEqualTo("동해기업");
		assertThat(deleted.reservations()).isEqualTo(1);
		assertThat(deleted.approvals()).isEqualTo(1);
		assertThat(booths.findById(booth.getId())).isEmpty();
		assertThat(reservations.findByBoothId(booth.getId())).isEmpty();
		assertThat(approvals.findByBoothIdOrderByNameAsc(booth.getId())).isEmpty();
	}

	/** 사람은 남는다 — 다른 부스 예약이 그 사람을 가리키고 있고, 연락처는 행사 기록이다. */
	@Test
	void 부스를_지워도_방문자는_남는다() {
		service.book(booth.getId(), slot, "홍길동", "01012345678");
		admin.setActive(booth.getId(), false);

		admin.deleteBooth(booth.getId());

		assertThat(visitors.findAll()).singleElement()
			.satisfies(v -> assertThat(v.getName()).isEqualTo("홍길동"));
	}

	@Test
	void 남의_부스_예약은_건드리지_않는다() {
		Booth other = booths.save(new Booth("서해기업", "B-03", null, booth.getEventDate(),
			LocalTime.of(10, 0), LocalTime.of(17, 0), 30, 5));
		service.book(booth.getId(), slot, "홍길동", "01012345678");
		service.book(other.getId(), slot, "김철수", "01033334444");
		admin.setActive(booth.getId(), false);

		admin.deleteBooth(booth.getId());

		assertThat(reservations.findByBoothId(other.getId())).hasSize(1);
	}

	@Test
	void 없는_부스를_지우면_404다() {
		assertThatThrownBy(() -> admin.deleteBooth(999_999L))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND));
	}
}
