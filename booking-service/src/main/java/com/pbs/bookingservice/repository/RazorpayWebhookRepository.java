package com.pbs.bookingservice.repository;

import com.pbs.bookingservice.entity.RazorpayWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RazorpayWebhookRepository extends JpaRepository<RazorpayWebhook, String> {
    List<RazorpayWebhook> findTop50ByProcessedFalseOrderById();

    @Modifying
    @Query(value = "INSERT INTO razorpay_webhook(id,payload,processed) VALUES (:id,:payload,false) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    void insert(@Param("id") String id, @Param("payload") String payload);
}
