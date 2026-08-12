@org.springframework.modulith.ApplicationModule(
    displayName = "Notification",
    allowedDependencies = {
        "shared :: api",
        "operations :: api",
        "eventing :: api",
        "identity :: api",
        "merchant :: api",
        "delivery :: api"
    }
)
package io.github.kdh949.beanflow.notification;
