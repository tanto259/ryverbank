package cs203.g1t7.users;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserNotFoundException(Integer id) {
        super("There is no user with specified id: " + id + " was found.");
    }
    
    public UserNotFoundException() {
        super("There is no user found.");
    }

}