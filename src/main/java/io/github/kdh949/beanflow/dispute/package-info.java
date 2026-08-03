@org.springframework.modulith.ApplicationModule(
    displayName = "Dispute",
    allowedDependencies = {
        "shared :: api",
        "settlement :: api",
        "identity :: api",
        "operations :: api",
        "eventing :: api"
    }
)
package io.github.kdh949.beanflow.dispute;
