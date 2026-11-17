package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ShowRepository extends JpaRepository<Show, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Show b WHERE b.id = :id")
    java.util.Optional<Show> findLockedById(Long id);


    @Query("SELECT s FROM Show s WHERE s.movieId = :movieId")
    java.util.List<Show> findByMovieId(Long movieId);

    @Query("SELECT s FROM Show s WHERE s.theatreId = :theatreId")
    java.util.List<Show> findByTheatreId(Long theatreId);

    @Query("SELECT s FROM Show s WHERE s.movieId = :movieId AND s.theatreId = :theatreId")
    java.util.List<Show> findByMovieIdAndTheatreId(Long movieId, Long theatreId);


}
