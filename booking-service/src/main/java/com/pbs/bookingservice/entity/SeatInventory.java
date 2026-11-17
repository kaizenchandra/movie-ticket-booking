package com.pbs.bookingservice.entity;

import com.pbs.bookingservice.entity.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

@Entity
@Table(name = "SEAT_INVENTORY")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
@EqualsAndHashCode
public class SeatInventory implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Double price;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus seatStatus;
    @Column(nullable = false)
    private String seatNumber;

    @ManyToOne
    @JoinColumn(name = "SHOW_ID", nullable = false)
    private Show show;


}
