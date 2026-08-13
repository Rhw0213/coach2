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

	private final BoothRepository booths;
	private final VisitorRepository visitors;
	private final ReservationRepository reservations;
	private final ReservationWriter writer;

	public ReservationService(BoothRepository booths, VisitorRepository visitors,
	                          ReservationRepository reservations, ReservationWriter writer) {
		this.booths = booths;
		this.visitors = visitors;
		this.reservations = reservations;
		this.writer = writer;
	}

	/** 예약 결과. 발급된 토큰을 함께 돌려준다(이후 조회·취소의 열쇠). */
	public record BookResult(Reservation reservation, String visitorToken) {
	}

	@Transactional(readOnly = true)
	public List<Booth> activeBooths() {
		return booths.findByActiveTrueOrderByBoothNoAsc();
	}

	@Transactional(readOnly = true)
	public Booth booth(Long boothId) {
		return booths.findById(boothId).orElseThrow(() -> notFound("부스를 찾을 수 없습니다"));
	}

	@Transactional(readOnly = true)
	public List<Instant> availableSlots(Long boothId) {
		Booth booth = activeBooth(boothId);
		Set<Instant> taken = reservations
			.findByBoothIdAndStatus(boothId, ReservationStatus.RESERVED)
			.stream()
			.map(Reservation::getStartTime)
			.collect(Collectors.toSet());

		Instant now = Instant.now();
		return Slots.forBooth(booth).stream()
			.filter(slot -> slot.isAfter(now))
			.filter(slot -> !taken.contains(slot))
			.toList();
	}

	@Transactional
	public BookResult book(Long boothId, Instant startTime, String name, String rawPhone) {
		Booth booth = activeBooth(boothId);

		String phone = PhoneNumbers.normalize(rawPhone);
		if (phone == null) {
			throw badRequest("연락처를 확인해 주세요");
		}
		if (name == null || name.isBlank()) {
			throw badRequest("이름을 입력해 주세요");
		}
		// 클라이언트가 보낸 시각이 실제로 이 부스의 슬롯인지 확인한다.
		// 이 검사가 없으면 운영시간 밖이나 슬롯 경계에 걸치지 않는 임의 시각을 밀어넣을 수 있다.
		if (!Slots.forBooth(booth).contains(startTime)) {
			throw badRequest("예약할 수 없는 시간입니다");
		}
		if (!startTime.isAfter(Instant.now())) {
			throw badRequest("지난 시간은 예약할 수 없습니다");
		}

		Visitor visitor = findOrCreateVisitor(name, phone);

		// 아래 두 검사는 흔한 경우에 무엇이 문제인지 알려주기 위한 것이다.
		// 동시 요청은 이걸로 막지 못한다 — 진짜 방어선은 Reservation의 유니크 제약이다.
		if (reservations.existsByBoothIdAndStartTimeAndStatus(
				boothId, startTime, ReservationStatus.RESERVED)) {
			throw conflict("이미 예약된 시간입니다");
		}
		if (reservations.existsByVisitorIdAndStartTimeAndStatus(
				visitor.getId(), startTime, ReservationStatus.RESERVED)) {
			throw conflict("같은 시간에 다른 부스를 이미 예약하셨습니다");
		}

		try {
			Reservation saved = writer.insert(
				new Reservation(boothId, visitor.getId(), startTime, booth.getSlotMinutes()));
			return new BookResult(saved, visitor.getToken());
		} catch (DataIntegrityViolationException e) {
			// 위 검사와 INSERT 사이에 다른 요청이 같은 슬롯을 가져간 경우.
			// 부스 슬롯 충돌인지 본인 중복인지는 구분하지 않는다 — 제약 이름을 파싱하는 건
			// DB에 종속적이라 깨지기 쉽고, 사용자가 할 행동은 어느 쪽이든 같다.
			throw conflict("방금 다른 예약이 확정되었습니다. 다른 시간을 선택해 주세요");
		}
	}

	@Transactional(readOnly = true)
	public List<Reservation> myReservations(String token) {
		return reservations.findByVisitorIdOrderByStartTimeDesc(visitorByToken(token).getId());
	}

	@Transactional
	public void cancel(Long reservationId, String token) {
		Visitor visitor = visitorByToken(token);
		Reservation reservation = reservations.findById(reservationId)
			.orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
		// 남의 예약이면 403이 아니라 404를 준다 — 토큰을 넣어보며 예약의 존재 여부를
		// 확인하는 것을 막는다.
		if (!reservation.isOwnedBy(visitor.getId())) {
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
	public Visitor visitorByToken(String token) {
		if (token == null || token.isBlank()) {
			throw notFound("예약을 찾을 수 없습니다");
		}
		return visitors.findByToken(token).orElseThrow(() -> notFound("예약을 찾을 수 없습니다"));
	}

	private Visitor findOrCreateVisitor(String name, String phone) {
		return visitors.findByPhone(phone).orElseGet(() -> {
			try {
				return writer.insertVisitor(new Visitor(name, phone));
			} catch (DataIntegrityViolationException e) {
				// 같은 번호로 동시에 첫 예약이 들어온 경우 — 먼저 들어간 쪽을 쓴다.
				return visitors.findByPhone(phone).orElseThrow(() -> e);
			}
		});
	}

	private Booth activeBooth(Long boothId) {
		Booth booth = booth(boothId);
		if (!booth.isActive()) {
			throw notFound("부스를 찾을 수 없습니다");
		}
		return booth;
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
