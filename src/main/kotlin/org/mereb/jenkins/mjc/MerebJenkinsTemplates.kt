package org.mereb.jenkins.mjc

object MerebJenkinsTemplates {
    private val recipes = listOf("build", "package", "image", "service", "microfrontend", "terraform")

    fun supportedRecipes(): List<String> = recipes

    fun configTemplate(recipe: String): String = when (recipe) {
        "build" -> """
            version: 1
            recipe: build
            preset: node
            
            image: false
            
            build:
              pnpm:
                nodeVersion: 20.19.2
                packageDir: .
                packageName: your-package
                steps:
                  - type: test
                    name: Run tests
                  - type: build
                    name: Build package
        """.trimIndent() + "\n"
        "package" -> """
            version: 1
            recipe: package
            preset: node
            
            image: false
            
            build:
              pnpm:
                nodeVersion: 20.19.2
                packageDir: .
                packageName: your-package
                steps:
                  - type: lint
                    name: Lint package
                  - type: test
                    name: Run tests
                  - type: build
                    name: Build package
            
            release:
              autoTag:
                enabled: true
                when: branch=main & !pr
                bump: patch
        """.trimIndent() + "\n"
        "image" -> """
            version: 1
            recipe: image
            
            build:
              stages:
                - name: Prepare
                  sh: echo "prepare image"
            
            image:
              repository: registry.example.com/your-image
              context: .
              dockerfile: Dockerfile
        """.trimIndent() + "\n"
        "service" -> """
            version: 1
            recipe: service
            preset: node
            
            delivery:
              mode: staged
            
            build:
              pnpm:
                nodeVersion: 20.19.2
                packageDir: .
                packageName: your-service
                steps:
                  - type: lint
                    name: Lint service
                  - type: test
                    name: Run tests
                  - type: build
                    name: Build service
            
            image:
              repository: registry.example.com/your-service
              context: .
              dockerfile: Dockerfile
            
            deploy:
              order: [dev, stg, prd]
              dev:
                namespace: apps-dev
                chart: app-chart
              stg:
                namespace: apps-stg
                chart: app-chart
              prd:
                namespace: apps-prd
                chart: app-chart
        """.trimIndent() + "\n"
        "microfrontend" -> """
            version: 1
            recipe: microfrontend
            preset: node
            
            delivery:
              mode: staged
            
            image: false
            
            build:
              pnpm:
                nodeVersion: 20.19.2
                packageDir: .
                packageName: your-microfrontend
                steps:
                  - type: lint
                    name: Lint remote
                  - type: test
                    name: Run tests
                  - type: build
                    name: Build remote
            
            microfrontend:
              name: your-microfrontend
              distDir: dist
              order: [dev, stg, prd]
              environments:
                dev:
                  bucket: cdn-dev
                  publicBase: https://cdn-dev.example.com
                stg:
                  bucket: cdn-stg
                  publicBase: https://cdn-stg.example.com
                prd:
                  bucket: cdn
                  publicBase: https://cdn.example.com
        """.trimIndent() + "\n"
        "terraform" -> """
            version: 1
            recipe: terraform
            
            image: false
            
            build:
              stages:
                - name: Validate Terraform
                  sh: terraform fmt -check
            
            terraform:
              path: terraform
              version: 1.13.4
              autoInstall: true
              order: [dev]
              environments:
                dev:
                  when: branch=main & !pr
                  path: envs/dev
        """.trimIndent() + "\n"
        else -> configTemplate("build")
    }

    fun snippetTemplates(): List<Pair<String, String>> = listOf(
        "service recipe" to configTemplate("service"),
        "package recipe" to configTemplate("package"),
        "image block" to """
            image:
              repository: registry.example.com/your-image
              context: .
              dockerfile: Dockerfile
        """.trimIndent(),
        "deploy environment" to """
            dev:
              namespace: apps-dev
              chart: app-chart
        """.trimIndent(),
        "microfrontend environment" to """
            dev:
              bucket: cdn-dev
              publicBase: https://cdn-dev.example.com
        """.trimIndent(),
        "release autoTag" to """
            release:
              autoTag:
                enabled: true
                when: branch=main & !pr
                bump: patch
        """.trimIndent(),
    )
}

