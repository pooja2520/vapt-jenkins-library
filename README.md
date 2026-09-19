# Maximus VAPT Jenkins Shared Library

This repository contains a Jenkins Shared Library for triggering Maximus VAPT security scans from any Jenkins Pipeline. It is completely zero-dependency and does not require any third-party Jenkins plugins or Python packages.

## Setup in Jenkins

1. Go to **Manage Jenkins** > **System**.
2. Scroll down to **Global Pipeline Libraries**.
3. Click **Add**.
4. Set Name to `vapt-jenkins-library`.
5. Set Default version to `main` (or whichever branch you push this code to).
6. Under **Retrieval method**, select **Modern SCM**.
7. Provide the Git repository URL for this repository.
8. Click **Save**.

## Usage in Pipeline

In your `Jenkinsfile`, import the library at the top and call the step:

```groovy
@Library('vapt-jenkins-library') _

pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                echo 'Building application...'
            }
        }
        stage('Security Scan') {
            steps {
                withCredentials([string(credentialsId: 'vapt-api-key', variable: 'API_KEY')]) {
                    maximusVaptScan(
                        vaptServerUrl: 'https://vapt.maximusatlas.com', // Optional: defaults to this URL
                        apiKey: env.API_KEY,
                        target: 'https://github.com/my-org/my-repo'
                    )
                }
            }
        }
    }
}
```

## Features
- **Zero-Dependency**: Uses embedded Python script with the built-in `urllib` package. No need to install `requests` via pip!
- **Automatic Polling**: Will poll the VAPT server every 10 seconds until the scan completes.
- **Auto-Artifacting**: Downloads both HTML and Excel reports and automatically attaches them to the Jenkins Build UI using `archiveArtifacts`.
