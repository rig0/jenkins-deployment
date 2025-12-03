<div align="center">

# Jenkins Deployment Library

![CI/CD](https://img.shields.io/badge/CI%2FCD-239120?logo=gitlab&logoColor=white)
![Jenkins](https://img.shields.io/badge/Jenkins-D24939?logo=jenkins&logoColor=white)
![Groovy](https://img.shields.io/badge/Groovy-5a92a7?logo=apachegroovy&logoColor=white)

![Release](https://img.shields.io/github/v/release/rig0/jenkins-deployment?labelColor=222&color=80ff63)
![Stability](https://img.shields.io/badge/stability-stable-80ff63?labelColor=222)
![Maintained](https://img.shields.io/badge/maintained-yes-80ff63?labelColor=222)
![GitHub last commit](https://img.shields.io/github/last-commit/rig0/jenkins-deployment?labelColor=222&color=80ff63)


**A Jenkins shared library for deploying Python-based applications following the **System Dependencies → Python Install → Config → Run** pattern.**

</div>

## Features

- **Secure Credential Handling**: Template-based configuration with automatic credential masking
- **Reusable Functions**: Generic deployment functions that work across multiple projects
- **Comprehensive Error Handling**: Fail-fast with meaningful error messages
- **Production-Ready**: Includes health checks, process validation, and cleanup
- **Security-First Design**: Input validation, least-privilege execution, no credential leaks

## Installation

1. Add this library to your Jenkins instance as a Global Pipeline Library
2. Configure in Jenkins: `Manage Jenkins → Configure System → Global Pipeline Libraries`
   - Name: `jenkins-deployment`
   - Default version: `main`
   - Retrieval method: Modern SCM → Git
   - Project Repository: `https://github.com/rig0/jenkins-deployment`

## Usage

### Basic Example

```groovy
@Library(['jenkins-deployment']) _

pipeline {
  agent any

  parameters {
    string(name: 'REMOTE_HOST', defaultValue: 'app.example.com', description: 'Target host')
    string(name: 'WORKDIR', defaultValue: '/opt/myapp', description: 'Working directory')
    string(name: 'REPO_URL', defaultValue: 'https://github.com/user/myapp.git', description: 'Repository URL')
  }

  stages {
    stage('Clone Repository') {
      steps {
        script {
          deploymentLib.cloneRepository(
            params.REMOTE_HOST,
            'ssh-credentials-id',
            params.WORKDIR,
            params.REPO_URL,
            'main'
          )
        }
      }
    }

    stage('Install System Dependencies') {
      steps {
        script {
          def packages = ['python3', 'python3-pip', 'gcc', 'make']
          deploymentLib.installSystemDependencies(
            params.REMOTE_HOST,
            'ssh-credentials-id',
            packages,
            'dnf'
          )
        }
      }
    }

    stage('Run Python Installer') {
      steps {
        script {
          deploymentLib.runPythonInstaller(
            params.REMOTE_HOST,
            'ssh-credentials-id',
            params.WORKDIR
          )
        }
      }
    }

    stage('Configure Application') {
      steps {
        script {
          def configTemplate = readFile('config.template.ini')

          withCredentials([
            usernamePassword(credentialsId: 'db-creds', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS'),
            string(credentialsId: 'api-token', variable: 'API_TOKEN')
          ]) {
            def mapping = [
              '{{DB_USER}}': env.DB_USER,
              '{{DB_PASS}}': env.DB_PASS,
              '{{API_TOKEN}}': env.API_TOKEN
            ]

            deploymentLib.prepareEnvironment(
              params.REMOTE_HOST,
              'ssh-credentials-id',
              params.WORKDIR,
              configTemplate,
              'config/app.ini',
              mapping
            )
          }
        }
      }
    }

    stage('Launch Application') {
      steps {
        script {
          def environment = [
            APP_MODE: 'production',
            LOG_LEVEL: 'info'
          ]

          def result = deploymentLib.launchApplication(
            params.REMOTE_HOST,
            'ssh-credentials-id',
            params.WORKDIR,
            'main.py',
            environment,
            '--production'
          )

          echo "Application launched with PID: ${result.pid}"
        }
      }
    }
  }
}
```

## Function Reference

### `cloneRepository()`

Clone or update a Git repository on a remote host.

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `workdir` (String): Remote working directory
- `repoUrl` (String): Git repository URL
- `branch` (String, optional): Branch to checkout (default: 'main')
- `cleanClone` (Boolean, optional): Force fresh clone (default: true)

**Returns:** `true` if successful

**Example:**
```groovy
deploymentLib.cloneRepository(
  'app.example.com',
  'ssh-creds',
  '/opt/myapp',
  'https://github.com/user/repo.git',
  'develop'
)
```

---

### `installSystemDependencies()`

Install system packages using DNF, YUM, or APT.

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `packages` (List<String>): Package names to install
- `packageManager` (String, optional): Package manager (default: 'dnf')

**Returns:** `true` if successful

**Security Features:**
- Validates package names to prevent command injection
- Only installs packages that aren't already present
- Uses sudo with password from SSH credentials

**Example:**
```groovy
def packages = [
  'python3',
  'python3-pip',
  'gcc',
  'make',
  'libpq-devel'
]

deploymentLib.installSystemDependencies(
  'app.example.com',
  'ssh-creds',
  packages,
  'dnf'
)
```

---

### `runPythonInstaller()`

Execute a Python installer script (typically `install.py` or `setup.py`).

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `workdir` (String): Remote working directory
- `installerScript` (String, optional): Installer script name (default: 'install.py')
- `pythonBin` (String, optional): Python binary (default: 'python3')
- `additionalArgs` (String, optional): Additional arguments

**Returns:** `true` if successful

**Example:**
```groovy
deploymentLib.runPythonInstaller(
  'app.example.com',
  'ssh-creds',
  '/opt/myapp',
  'install.py',
  'python3.11',
  '--no-cache'
)
```

---

### `prepareEnvironment()`

Deploy configuration files using template substitution with Jenkins credentials.

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `workdir` (String): Remote working directory
- `configTemplate` (String): Configuration template content
- `configPath` (String): Remote path for config file (relative to workdir)
- `credentialMapping` (Map<String, String>): Placeholder to value mapping

**Returns:** `true` if successful

**Security Features:**
- Template-based approach prevents injection attacks
- All credentials are masked in Jenkins logs
- Config files created with restrictive permissions (600)
- Validates against directory traversal attacks

**Example:**
```groovy
def template = readFile('templates/config.ini')

withCredentials([
  usernamePassword(credentialsId: 'db-creds', usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS'),
  string(credentialsId: 'api-key', variable: 'API_KEY')
]) {
  def mapping = [
    '{{DATABASE_USER}}': env.DB_USER,
    '{{DATABASE_PASS}}': env.DB_PASS,
    '{{API_KEY}}': env.API_KEY,
    '{{ENVIRONMENT}}': 'production'
  ]

  deploymentLib.prepareEnvironment(
    'app.example.com',
    'ssh-creds',
    '/opt/myapp',
    template,
    'config/production.ini',
    mapping
  )
}
```

**Template Example:**
```ini
[database]
host = postgres.example.com
port = 5432
username = {{DATABASE_USER}}
password = {{DATABASE_PASS}}

[api]
key = {{API_KEY}}
environment = {{ENVIRONMENT}}
```

---

### `launchApplication()`

Start an application in background mode (survives SSH disconnect).

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `workdir` (String): Remote working directory
- `scriptPath` (String): Script to execute (relative to workdir)
- `environment` (Map, optional): Environment variables to export
- `scriptArgs` (String, optional): Script arguments (default: '--deploy')
- `pythonBin` (String, optional): Python binary for .py scripts (default: 'python3')
- `logPath` (String, optional): Log file path (default: '/tmp/app-deployment.log')

**Returns:** Map with keys:
- `success` (Boolean): Whether launch succeeded
- `pid` (String): Process ID
- `logPath` (String): Path to log file
- `error` (String, optional): Error message if failed

**Features:**
- Kills existing instances before launching
- Uses nohup to survive SSH disconnect
- Health check to verify process started
- Comprehensive logging

**Example:**
```groovy
def env = [
  APP_MODE: 'production',
  LOG_LEVEL: 'info',
  WORKERS: '4'
]

def result = deploymentLib.launchApplication(
  'app.example.com',
  'ssh-creds',
  '/opt/myapp',
  'main.py',
  env,
  '--workers 4 --bind 0.0.0.0:8000',
  'python3.11',
  '/var/log/myapp/app.log'
)

if (result.success) {
  echo "Application running with PID: ${result.pid}"
  echo "Logs: ${result.logPath}"
} else {
  error "Launch failed: ${result.error}"
}
```

---

### `runRemoteCommand()`

Execute arbitrary commands on a remote host with output capture and exit code handling.

**Parameters:**
- `remoteHost` (String): Target hostname or IP address
- `sshCredentialsId` (String): Jenkins SSH credentials ID
- `workdir` (String): Remote working directory where command executes
- `commandScript` (String): Shell script to execute (can be multiline)
- `waitForCompletion` (Boolean, optional): Wait for completion (default: true)
- `timeoutSeconds` (Integer, optional): Command timeout in seconds (default: 300)

**Returns:** Map with keys:
- `exitCode` (Integer): Command exit code
- `output` (String): Command stdout/stderr output
- `success` (Boolean): True if exitCode == 0
- `error` (String, optional): Error message if failed

**Use Cases:**
- Running unit tests
- Database migrations
- Health checks
- Custom deployment tasks

**Example:**
```groovy
def result = deploymentLib.runRemoteCommand(
  'app.example.com',
  'ssh-creds',
  '/opt/myapp',
  '''
  source venv/bin/activate
  pytest -v --junit-xml=test-results.xml
  ''',
  true  // Wait for completion
)

if (result.exitCode == 0) {
  echo "✅ Tests passed"
  currentBuild.result = 'SUCCESS'
} else {
  echo "❌ Tests failed with exit code: ${result.exitCode}"
  echo "Output: ${result.output}"
  currentBuild.result = 'UNSTABLE'
}
```

## Configuration Template Best Practices

### Use Placeholder Syntax

Use a consistent placeholder syntax like `{{VARIABLE_NAME}}`:

```ini
[database]
host = {{DB_HOST}}
port = {{DB_PORT}}
username = {{DB_USER}}
password = {{DB_PASS}}
```

### Keep Templates in Source Control

Store templates in your pipeline repository:

```
pipeline-repo/
├── Jenkinsfile
└── templates/
    ├── dev-config.ini
    ├── staging-config.ini
    └── prod-config.ini
```

### Load Templates Dynamically

```groovy
def environment = params.ENVIRONMENT  // 'dev', 'staging', 'prod'
def template = readFile("templates/${environment}-config.ini")
```

### Validate Unreplaced Placeholders

The `prepareEnvironment()` function automatically warns about unreplaced placeholders:

```
⚠️ WARNING: Unreplaced placeholders found in config:
   - {{FEATURE_FLAG}}
   - {{OPTIONAL_SETTING}}
This may indicate missing credential mappings.
```

## Security Considerations

### Credential Masking

All credentials passed through `withCredentials` are automatically masked in Jenkins console output:

```
[INFO] Deploying config with credentials...
[INFO] Username: ****
[INFO] Password: ****
```

### Input Validation

The library validates all inputs to prevent injection attacks:

```groovy
// Package names must match: [a-zA-Z0-9._-]+
def packages = ['python3', 'gcc']  // ✅ Valid
def packages = ['python3; rm -rf /']  // ❌ Rejected

// Config paths cannot contain '..' or start with '/'
def configPath = 'config/app.ini'  // ✅ Valid
def configPath = '../../../etc/passwd'  // ❌ Rejected
```

### File Permissions

Configuration files are created with restrictive permissions:

```bash
-rw------- 1 user user 1234 Nov 17 10:00 config.ini  # 600 permissions
```

### No Credentials in Logs

Credentials never appear in:
- Jenkins console output (masked by `withCredentials`)
- Remote command history (base64 transfer)
- Process listings (environment variables only visible to owner)

## Troubleshooting

### "Invalid package names detected"

**Cause:** Package names contain invalid characters.

**Solution:** Ensure package names only contain `[a-zA-Z0-9._+-]`:

```groovy
// ✅ Correct
def packages = ['python3-pip', 'gcc-c++']

// ❌ Wrong
def packages = ['python3; echo hack']
```

### "Script not found: install.py"

**Cause:** Installer script doesn't exist in the workdir.

**Solution:** Verify the repository contains the installer:

```groovy
deploymentLib.cloneRepository(...)  // Clone first
deploymentLib.runPythonInstaller(...)  // Then run installer
```

### "Application process exited immediately"

**Cause:** Application crashed on startup.

**Solution:** Check the log file specified in `launchApplication()`:

```bash
ssh user@host 'tail -100 /tmp/app-deployment.log'
```

### "Unreplaced placeholders found in config"

**Cause:** Template contains placeholders that weren't provided in `credentialMapping`.

**Solution:** Add all required credentials to the mapping:

```groovy
def mapping = [
  '{{DB_USER}}': env.DB_USER,
  '{{DB_PASS}}': env.DB_PASS,
  '{{MISSING_VAR}}': 'default-value'  // Add this
]
```

## Advanced Examples

### Multi-Environment Deployment

```groovy
pipeline {
  agent any

  parameters {
    choice(name: 'ENVIRONMENT', choices: ['dev', 'staging', 'prod'], description: 'Target environment')
  }

  stages {
    stage('Deploy') {
      steps {
        script {
          def config = [
            dev: [
              host: 'dev.example.com',
              packages: ['python3', 'gcc'],
              template: 'dev-config.ini'
            ],
            staging: [
              host: 'staging.example.com',
              packages: ['python3', 'python3-pip', 'gcc', 'postgresql-devel'],
              template: 'staging-config.ini'
            ],
            prod: [
              host: 'prod.example.com',
              packages: ['python3', 'python3-pip', 'gcc', 'postgresql-devel', 'redis'],
              template: 'prod-config.ini'
            ]
          ][params.ENVIRONMENT]

          // Clone
          deploymentLib.cloneRepository(config.host, 'ssh-creds', '/opt/app', env.GIT_URL)

          // Install dependencies
          deploymentLib.installSystemDependencies(config.host, 'ssh-creds', config.packages)

          // Run installer
          deploymentLib.runPythonInstaller(config.host, 'ssh-creds', '/opt/app')

          // Configure
          def template = readFile("templates/${config.template}")
          withCredentials([
            usernamePassword(credentialsId: "${params.ENVIRONMENT}-db", usernameVariable: 'DB_USER', passwordVariable: 'DB_PASS')
          ]) {
            deploymentLib.prepareEnvironment(
              config.host,
              'ssh-creds',
              '/opt/app',
              template,
              'config/app.ini',
              ['{{DB_USER}}': env.DB_USER, '{{DB_PASS}}': env.DB_PASS]
            )
          }

          // Launch
          deploymentLib.launchApplication(config.host, 'ssh-creds', '/opt/app', 'main.py')
        }
      }
    }
  }
}
```

### Parallel Deployment to Multiple Hosts

```groovy
stage('Deploy to Fleet') {
  steps {
    script {
      def hosts = ['app1.example.com', 'app2.example.com', 'app3.example.com']

      parallel hosts.collectEntries { host ->
        ["Deploy to ${host}": {
          deploymentLib.cloneRepository(host, 'ssh-creds', '/opt/app', env.GIT_URL)
          deploymentLib.installSystemDependencies(host, 'ssh-creds', ['python3', 'gcc'])
          deploymentLib.runPythonInstaller(host, 'ssh-creds', '/opt/app')
          deploymentLib.launchApplication(host, 'ssh-creds', '/opt/app', 'main.py')
        }]
      }
    }
  }
}
```

### Blue-Green Deployment

```groovy
stage('Blue-Green Deploy') {
  steps {
    script {
      def blueHost = 'blue.example.com'
      def greenHost = 'green.example.com'
      def activeHost = sh(script: 'get-active-host.sh', returnStdout: true).trim()
      def targetHost = (activeHost == blueHost) ? greenHost : blueHost

      echo "Deploying to inactive host: ${targetHost}"

      // Deploy to inactive host
      deploymentLib.cloneRepository(targetHost, 'ssh-creds', '/opt/app', env.GIT_URL)
      deploymentLib.installSystemDependencies(targetHost, 'ssh-creds', ['python3'])
      deploymentLib.runPythonInstaller(targetHost, 'ssh-creds', '/opt/app')
      deploymentLib.prepareEnvironment(targetHost, 'ssh-creds', '/opt/app', template, 'config.ini', mapping)
      def result = deploymentLib.launchApplication(targetHost, 'ssh-creds', '/opt/app', 'main.py')

      // Health check
      sleep 10
      def healthy = sh(script: "curl -f http://${targetHost}:8000/health", returnStatus: true) == 0

      if (healthy) {
        echo "Switching traffic to ${targetHost}"
        sh "switch-traffic.sh ${targetHost}"
      } else {
        error "Health check failed on ${targetHost}"
      }
    }
  }
}
```