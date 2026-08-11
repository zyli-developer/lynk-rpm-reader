# Third-party notices

The application resolves the following libraries through Gradle; their source code is not copied into this repository.

| Component | Declared version | License | Source |
| --- | --- | --- | --- |
| gRPC-Java (`grpc-netty`, `grpc-stub`) | 1.80.0 | Apache-2.0 | https://github.com/grpc/grpc-java |
| Netty | 4.1.136.Final BOM | Apache-2.0 | https://github.com/netty/netty |

The resolved runtime graph also includes gRPC modules and transitive components from Google Guava, Gson, Error Prone annotations, JSpecify, J2ObjC annotations, Animal Sniffer annotations, PerfMark, and JSR-305. These components retain their respective upstream licenses and notices; they are not relicensed by this project.

Gradle may resolve additional transitive dependencies. Distributors should inspect the resolved dependency graph and retain all notices required by those dependencies when distributing APKs or other binary packages.
