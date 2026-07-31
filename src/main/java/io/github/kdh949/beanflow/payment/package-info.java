@org.springframework.modulith.ApplicationModule(
    displayName = "Payment",
    allowedDependencies = {"shared :: api", "operations :: api", "eventing :: api"}
)
package io.github.kdh949.beanflow.payment;
