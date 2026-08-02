@org.springframework.modulith.ApplicationModule(
    displayName = "Settlement",
    allowedDependencies = {
        "shared :: api",
        "merchant :: api",
        "ordering :: api",
        "payment :: api",
        "operations :: api",
        "identity :: api",
        "eventing :: api"
    }
)
package io.github.kdh949.beanflow.settlement;
