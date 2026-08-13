package com.interview.coach2.reservation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReservationService {

	private final CoachRepository coaches;
	private final CustomerRepository customers;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;

	public ReservationService(CoachRepository coaches, CustomerRepository customers,
	                          ReservationRepository reservations, ReservationWriter writer) {
		this.coaches = coaches;
		this.customers = customers;
		this.reservations = reservations;
		this.writer = writer;
	}

	/** 예약 결과. 첫 예약이면 발급된 토큰을 함께 돌려준다(이후 조회·취소의 열쇠). */
	public record BookResult(Reservation reservation, String customerToken) {
	}

	@Transactional(readOnly = true)
	public List<Coach> activeCoaches() {
		return coaches.findByActiveTrueOrderByNameAsc();
	}

	@Transactional(readOnly = true)
	public List<Instant> availableSlots(Long coachId, LocalDate date) {
		Coach coach = activeCoach(coachId);
		List<Instant> all = Slots.forDate(coach, date);
		if (all.isEmpty()) {
			return List.of();
		}
		Set<Instant> taken = reservations
			.findByCoachIdAndStatusAndStartTimeGreaterThanEqualAndStartTimeLessThan(
				coachId, ReservationStatus.RESERVED, Slots.startOfDay(date), Slots.endOfDay(date))
			.stream()
			.map(Reservation::getStartTime)
			.collect(Collectors.toSet());

		Instant now = Instant.now();
		return all.stream()
			.filter(slot -> slot.isAfter(now))
			.filter(slot -> !taken.contains(slot))
			.toList();
	}

	@Transactional
	public BookResult book(Long coachId, Instant startTime, String name, String rawPhone) {
		Coach coach = activeCoach(coachId);

		String phone = PhoneNumbers.normalize(rawPhone);
		if (phone == null) {
			throw badRequest("연락처를 확인해 주세요");
		}
		if (name == null || name.isBlank()) {
			throw badRequest("이름을 입력해 주세요");
		}
		// 클라이언트가 보낸 시각이 실제로 이 코치의 슬롯인지 확인한다.
		// 이 검사가 없으면 근무시간 밖이나 슬롯 경계에 걸치지 않는 임의 시각을 밀어넣을 수 있다.
		LocalDate date = LocalDate.ofInstant(startTime, Slots.ZONE);
		if (!Slots.forDate(coach, date).contains(startTime)) {
			throw badRequest("예약할 수 없는 시간입니다");
		}
		if (!startTime.isAfter(Instant.now())) {
			throw badRequest("지난 시간은 예약할 수 없습니다");
		}

		Customer customer = findOrCreateCustomer(name, phone);

		// 아래 두 검사는 흔한 경우에 무엇이 문제인지 알려주기 위한 것이다.
		// 동시 요청은 이걸로 막지 못한다 — 진짜 방어선은 Reservation의 유니크 제약이다.
		if (reservations.existsByCoachIdAndStartTimeAndStatus(
				coachId, startTime, ReservationStatus.RESERVED)) {
			throw conflict("이미 예약된 시간입니다");
		}
		if (reservations.existsByCustomerIdAndStartTimeAndStatus(
				customer.getId(), startTime, ReservationStatus.RESERVED)) {
			throw conflict("같은 시간에 이미 다른 예약이 있습니다");
		}

		try {
			Reservation saved = writer.insert(
				new Reservation(coachId, customer.getId(), startTime, coach.getSlotMinutes()));
			return new BookResult(saved, customer.getToken());
		} catch (DataIntegrityViolationException e) {
			// 위 검사와 INSERT 사이에 다른 요청이 같은 슬롯을 가져간 경우.
			// 코치 슬롯 충돌인지 본인 중복인지는 구분하지 않는다 — 제약 이름을 파싱하는 건
			// DB에 종속적이라 깨지기 쉽고, 사용자가 할 행동은 어느 쪽이든 같다.
			throw conflict("방금 다른 예약이 확정되었습니다. 다른 시간을 선택해 주세요");
		}
	}

	@Transactional(readOnly = true)
	public List<Reservation> myReservations(String token) {
		return reservations.findByCustomerIdOrderByStartTimeDesc(customerByToken(token).getId());
	}

	@Transactional
	public void cancel(Long reservationId, String token) {
		Customer customer = customerByToken(token);
		Reservation reservation = reservations.findById(reservationId)
			.orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
		// 남의 예약이면 403이 아니라 404를 준다 — 토큰을 넣어보며 예약의 존재 여부를
		// 확인하는 것을 막는다.
		if (!reservation.isOwnedBy(customer.getId())) {
			throw notFound("예약을 찾을 수 없습니다");
		}
		reservation.cancel();
	}

	@Transactional(readOnly = true)
	public List<Reservation> reservationsOn(LocalDate date) {
		return reservations.findByStatusAndStartTimeGreaterThanEqualAndStartTimeLessThanOrderByStartTime(
			ReservationStatus.RESERVED, Slots.startOfDay(date), Slots.endOfDay(date));
	}

	@Transactional(readOnly = true)
	public Customer customerByToken(String token) {
		if (token == null || token.isBlank()) {
			throw notFound("예약을 찾을 수 없습니다");
		}
		return customers.findByToken(token).orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
	}

	private Customer findOrCreateCustomer(String name, String phone) {
		return customers.findByPhone(phone).orElseGet(() -> {
			try {
				return writer.insertCustomer(new Customer(name, phone));
			} catch (DataIntegrityViolationException e) {
				// 같은 번호로 동시에 첫 예약이 들어온 경우 — 먼저 들어간 쪽을 쓴다.
				return customers.findByPhone(phone).orElseThrow(() -> e);
			}
		});
	}

	private Coach activeCoach(Long coachId) {
		Coach coach = coaches.findById(coachId).orElseThrow(() -> notFound("코치를 찾을 수 없습니다"));
		if (!coach.isActive()) {
			throw notFound("코치를 찾을 수 없습니다");
		}
		return coach;
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}

	private static ResponseStatusException notFound(String message) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
	}
}
