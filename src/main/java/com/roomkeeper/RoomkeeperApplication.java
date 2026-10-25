package com.roomkeeper;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RoomkeeperApplication {

	public static void main(String[] args) {
		// Run in UTC regardless of the host. The Postgres driver sends the JVM zone on
		// connect, and booking dates must not shift with the server's location.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(RoomkeeperApplication.class, args);
	}

}
