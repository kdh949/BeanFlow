@org.springframework.modulith.ApplicationModule(
    displayName = "Visitor Demo",
    allowedDependencies = {"shared :: api", "identity :: api", "merchant :: api", "fulfillment :: api",
        "loyalty :: api", "ordering :: api", "operations :: api"}
)
package io.github.kdh949.beanflow.demo;
