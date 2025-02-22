package cs203.g1t7.users;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {
    // define a derived query to find user by username
    Optional<User> findByUsername(String username);
    List<User> findByNric(String nric);
}