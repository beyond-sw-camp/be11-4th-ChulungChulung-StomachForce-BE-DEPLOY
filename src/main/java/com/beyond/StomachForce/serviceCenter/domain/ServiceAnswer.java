package com.beyond.StomachForce.serviceCenter.domain;

import com.beyond.StomachForce.Common.domain.BaseReservationTimeEntity;
import com.beyond.StomachForce.Common.domain.BaseTimeEntity;
import com.beyond.StomachForce.User.domain.User;
import jakarta.persistence.*;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Entity
@Builder
@Getter
@Setter
public class ServiceAnswer extends BaseReservationTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "post_id")
    private ServicePost servicePost;

    @Column(nullable = false)
    private String contents;


}
