package com.pbs.bookingservice.controller;

import com.pbs.bookingservice.common.ex.BookingNotFoundException;
import com.pbs.bookingservice.entity.SeatInventory;
import com.pbs.bookingservice.entity.Show;
import com.pbs.bookingservice.repository.SeatInventoryRepository;
import com.pbs.bookingservice.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shows")
@RequiredArgsConstructor
public class CatalogController {
    private final ShowRepository shows;
    private final SeatInventoryRepository seats;

    @GetMapping
    public List<Show> shows(@RequestParam(required = false) Long movieId,
                            @RequestParam(required = false) Long theatreId) {
        return shows.findAll().stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()) && s.getShowTime().isAfter(java.time.LocalDateTime.now()))
                .filter(s -> movieId == null || movieId.equals(s.getMovieId()))
                .filter(s -> theatreId == null || theatreId.equals(s.getTheatreId())).toList();
    }

    @GetMapping("/{showId}/seats")
    public List<SeatInventory> seats(@PathVariable Long showId) {
        if (!shows.existsById(showId)) throw new BookingNotFoundException("Show not found");
        return seats.findByShowIdOrderBySeatNumber(showId);
    }
}
