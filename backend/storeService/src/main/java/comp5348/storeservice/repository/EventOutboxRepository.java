package comp5348.storeservice.repository;

import comp5348.storeservice.model.EventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventOutboxRepository extends JpaRepository<EventOutbox, Long> {

    /** One batch of unsent events, oldest first (rule 3: send in id order, ~100 at a time) */
    List<EventOutbox> findTop100BySentAtIsNullOrderByIdAsc();
}
