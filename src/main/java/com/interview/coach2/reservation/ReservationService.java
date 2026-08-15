package com.interview.coach2.reservation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
		// 슬롯마다 몇 명이 찼는지 센다. 정원이 1이면 예전처럼 '있으면 마감'과 같아진다.
		Map<Instant, Long> taken = reservations
			.findByBoothIdAndStatus(boothId, ReservationStatus.RESERVED)
			.stream()
			.collect(Collectors.groupingBy(Reservation::getStartTime, Collectors.counting()));

		Instant now = Instant.now();
		return Slots.forBooth(booth).stream()
			.filter(slot -> slot.isAfter(now))
			.filter(slot -> taken.getOrDefault(slot, 0L) < booth.getCapacity())
			.toList();
	}

	/**
	 * ⚠ 이 메서드에 @Transactional을 붙이지 말 것. 커넥션 풀이 데드락한다.
	 *
	 * 실제 INSERT는 ReservationWriter가 REQUIRES_NEW로 자기 트랜잭션에서 한다(제약 위반이
	 * 바깥을 오염시키지 않게 하려고). 여기에 바깥 트랜잭션까지 있으면 요청 하나가 커넥션을
	 * 동시에 두 개 쥐게 되고, HikariCP 기본 풀 10개에서는 열 요청이 각자 하나씩 잡은 채
	 * 두 번째를 기다리다 전원이 타임아웃한다.
	 *
	 * 실측: 정원 1인 슬롯에 40명이 동시에 예약하면 40건 전부
	 * CannotCreateTransactionException(500)으로 실패했다. ConcurrentBookingTest가 이걸 지킨다.
	 *
	 * 트랜잭션이 없어도 안전하다. 이 메서드의 조회들은 서로 원자적일 필요가 없고
	 * (사전검사는 원래 경합을 막지 못한다 — 진짜 방어선은 유니크 제약이다),
	 * 엔티티에 지연로딩 연관관계가 하나도 없어 세션 밖에서 필드를 읽어도 문제가 없다.
	 */
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

		// 아래 세 검사는 흔한 경우에 무엇이 문제인지 알려주기 위한 것이다.
		// 동시 요청은 이걸로 막지 못한다 — 진짜 방어선은 Reservation의 유니크 제약이다.
		if (reservations.countByBoothIdAndStartTimeAndStatus(
				boothId, startTime, ReservationStatus.RESERVED) >= booth.getCapacity()) {
			// 1:1이면 '이미 예약된', 그룹이면 '정원이 찬' 것이다. 사용자가 읽는 문장이므로 구분한다.
			throw conflict(booth.getCapacity() == 1
				? "이미 예약된 시간입니다"
				: "이 시간은 정원이 모두 찼습니다");
		}
		if (reservations.existsByVisitorIdAndStartTimeAndStatus(
				visitor.getId(), startTime, ReservationStatus.RESERVED)) {
			throw conflict("같은 시간에 다른 부스를 이미 예약하셨습니다");
		}
		if (reservations.existsByVisitorIdAndBoothIdAndStatus(
				visitor.getId(), boothId, ReservationStatus.RESERVED)) {
			throw conflict("이 부스는 이미 예약하셨습니다. 다른 시간으로 바꾸시려면 기존 예약을 취소해 주세요");
		}

		// 1번 좌석부터 넣어 본다. 이미 찬 좌석은 유니크 제약이 거절하므로 다음 번호로 넘어간다.
		// 좌석 문자열이 정원 개수만큼만 존재하니, 동시 요청이 몇 개든 정원을 넘길 수 없다.
		for (int seat = 1; seat <= booth.getCapacity(); seat++) {
			try {
				Reservation saved = writer.insert(new Reservation(
					boothId, visitor.getId(), startTime, booth.getSlotMinutes(), seat));
				return new BookResult(saved, visitor.getToken());
			} catch (DataIntegrityViolationException e) {
				// 이 좌석은 방금 다른 사람이 가져갔다. 다음 좌석을 시도한다.
				// visitorSlotKey·visitorBoothKey 위반이면 어느 좌석을 시도해도 똑같이 실패하고
				// 아래 conflict로 떨어진다. 제약 이름을 파싱해 구분하지는 않는다 — DB에 종속적이라
				// 깨지기 쉽고, 사용자가 할 행동은 어느 쪽이든 같다.
			}
		}
		// 위 검사와 INSERT 사이에 다른 요청들이 남은 좌석을 모두 가져간 경우.
		throw conflict("방금 다른 예약이 확정되었습니다. 다른 시간을 선택해 주세요");
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

	/** 부스 담당자용. 토큰이 가리키는 부스와 그 부스의 유효한 예약만 돌려준다. */
	@Transactional(readOnly = true)
	public Booth boothByStaffToken(String staffToken) {
		if (staffToken == null || staffToken.isBlank()) {
			throw notFound("부스를 찾을 수 없습니다");
		}
		return booths.findByStaffToken(staffToken)
			.orElseThrow(() -> notFound("부스를 찾을 수 없습니다"));
	}

	@Transactional(readOnly = true)
	public List<Reservation> reservationsFor(Long boothId) {
		return reservations.findByBoothIdAndStatus(boothId, ReservationStatus.RESERVED).stream()
			.sorted(java.util.Comparator.comparing(Reservation::getStartTime))
			.toList();
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
