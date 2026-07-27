package io.github.kdh949.beanflow

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<BeanflowApplication>().with(TestcontainersConfiguration::class).run(*args)
}
