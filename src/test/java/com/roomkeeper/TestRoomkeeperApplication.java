package com.roomkeeper;

import org.springframework.boot.SpringApplication;

public class TestRoomkeeperApplication {

	public static void main(String[] args) {
		SpringApplication.from(RoomkeeperApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
