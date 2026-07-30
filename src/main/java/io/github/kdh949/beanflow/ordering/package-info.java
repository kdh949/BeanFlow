@org.springframework.modulith.ApplicationModule(
    displayName = "Ordering",
    allowedDependencies = {
        "shared :: api",
        "merchant :: api",
        "fulfillment :: api",
        "inventory :: api",
        "promotion :: api",
        "loyalty :: api",
        "payment :: api",
        "operations :: api",
        "identity :: api"
    }
)
package io.github.kdh949.beanflow.ordering;
