@org.springframework.modulith.ApplicationModule(
    displayName = "Inventory",
    allowedDependencies = {"shared :: api", "operations :: api", "eventing :: api"}
)
package io.github.kdh949.beanflow.inventory;
