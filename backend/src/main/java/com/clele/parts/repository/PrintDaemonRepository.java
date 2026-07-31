package com.clele.parts.repository;

import com.clele.parts.model.PrintDaemon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PrintDaemonRepository extends JpaRepository<PrintDaemon, Long> {

    @Query("select d from PrintDaemon d where d.lastSeenIp = :ip "
            + "and (d.owner.id = :userId or d.owner is null)")
    List<PrintDaemon> findVisibleToUserAtIp(@Param("userId") Long userId, @Param("ip") String ip);
}
