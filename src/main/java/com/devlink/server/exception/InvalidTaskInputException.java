package com.devlink.server.exception;

public class InvalidTaskInputException extends RuntimeException{

    public InvalidTaskInputException(String message) {
        super(message);
    }
}
