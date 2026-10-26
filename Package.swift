// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MedicalStaffScheduleTracker",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "MedicalStaffScheduleTracker",
            targets: ["MedicalStaffScheduleTracker"]
        ),
        .executable(
            name: "roster-tracker",
            targets: ["MedicalStaffScheduleTrackerCLI"]
        )
    ],
    targets: [
        .target(
            name: "MedicalStaffScheduleTracker",
            dependencies: [],
            path: "Sources/MedicalStaffScheduleTracker"
        ),
        .executableTarget(
            name: "MedicalStaffScheduleTrackerCLI",
            dependencies: ["MedicalStaffScheduleTracker"],
            path: "Sources/MedicalStaffScheduleTrackerCLI"
        ),
        .testTarget(
            name: "MedicalStaffScheduleTrackerTests",
            dependencies: ["MedicalStaffScheduleTracker"],
            path: "Tests/MedicalStaffScheduleTrackerTests",
            swiftSettings: [
                .unsafeFlags(["-F", "/Library/Developer/CommandLineTools/Library/Developer/Frameworks"])
            ],
            linkerSettings: [
                .unsafeFlags(["-F", "/Library/Developer/CommandLineTools/Library/Developer/Frameworks"])
            ]
        )
    ]
)
