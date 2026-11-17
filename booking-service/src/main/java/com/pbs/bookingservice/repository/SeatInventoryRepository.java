package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.SeatInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatInventory s WHERE s.show.id = :showId AND s.seatNumber IN :seatNumbers ORDER BY s.id")
    public List<SeatInventory> findByShowIdAndSeatNumberIn(Long showId, List<String> seatNumbers);

    List<SeatInventory> findByShowIdOrderBySeatNumber(Long showId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatInventory s WHERE s.id IN :list ORDER BY s.id")
    List<SeatInventory> findLockedByIds(List<Long> list);

    @Query("SELECT s FROM SeatInventory s WHERE s.id IN :list")
    List<SeatInventory> findAllById(List<Long> list);
}
