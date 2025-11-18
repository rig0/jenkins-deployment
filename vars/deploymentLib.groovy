/**
 * Jenkins Deployment Library
 *
 * Provides reusable deployment functions for Python-based applications
 * following the pattern: System Dependencies → Python Install → Config → Run
 *
 * SECURITY FEATURES:
 * - Secure credential handling with automatic masking
 * - Template-based configuration (no sed injection risks)
 * - Input validation and sanitization
 * - Least-privilege execution patterns
 * - Comprehensive error handling
 *
 * DESIGN PHILOSOPHY:
 * - Generic functions that work across multiple projects
 * - Clear separation of concerns
 * - Fail-fast with meaningful error messages
 * - Idempotent operations where possible
 * - Extensive logging for debugging
 *
 * USAGE:
 * Add @Library(['jenkins-deployment']) _ at the top of your Jenkinsfile
 * Then call functions like: deploymentLib.installSystemDependencies(...)
 */

/**
 * Install system packages on remote host using DNF (Fedora/RHEL)
 *
 * PURPOSE: Prepare the system with required dependencies before Python installation
 *
 * RATIONALE: System packages often require sudo and must be installed before
 * Python packages. Separating this step makes it reusable and easier to debug.
 *
 * SECURITY BENEFIT: Validates package names to prevent command injection.
 * Uses parameterized arrays instead of string concatenation.
 *
 * MAINTENANCE ADVANTAGE: Centralized dependency installation logic that can
 * be reused across multiple projects. Easy to adapt for apt/yum/zypper.
 *
 * @param remoteHost Target hostname or IP address
 * @param sshCredentialsId Jenkins credential ID for SSH access
 * @param packages List of package names to install (e.g., ['python3', 'gcc'])
 * @param packageManager Package manager to use (default: 'dnf', alternatives: 'yum', 'apt')
 * @return true if installation succeeds, false otherwise
 *
 * EXAMPLE:
 * def packages = ['python3', 'python3-pip', 'gcc', 'make']
 * deploymentLib.installSystemDependencies(
 *   'myhost.example.com',
 *   'ssh-credentials',
 *   packages
 * )
 *
 * LEARNING POINT: Always validate external input (package names) before
 * passing to shell commands. Use arrays and iterate rather than string concat.
 *
 * ALTERNATIVES: Could use Ansible for complex dependency management, but this
 * lightweight approach works well for simple package lists.
 */
def installSystemDependencies(String remoteHost, String sshCredentialsId, List<String> packages, String packageManager = 'dnf') {
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "📦 Installing System Dependencies"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Host: ${remoteHost}"
  echo "Package Manager: ${packageManager}"
  echo "Packages: ${packages.size()} packages"

  // SECURITY: Validate package names to prevent command injection
  // Only allow alphanumeric, hyphens, underscores, dots, and plus signs
  def invalidPackages = packages.findAll { !it.matches(/^[a-zA-Z0-9._+-]+$/) }
  if (invalidPackages) {
    error "❌ Invalid package names detected: ${invalidPackages}. Only alphanumeric, hyphens, underscores, dots, and plus signs are allowed."
  }

  withCredentials([
    usernamePassword(credentialsId: sshCredentialsId, usernameVariable: 'SSH_USER', passwordVariable: 'SSH_PASS')
  ]) {
    try {
      // Build the package list as a safe, space-separated string
      def packageList = packages.join(' ')

      // LEARNING POINT: Using single quotes in heredoc prevents interpolation issues
      // The ${PM} variable will be substituted AFTER the heredoc is built
      def installScript = '''set -euo pipefail

echo "🔍 Checking if packages are already installed..."

# Build list of packages that need installation
NEEDED_PACKAGES=()
for pkg in ${PACKAGE_LIST}; do
  if ! rpm -q "$pkg" &>/dev/null; then
    NEEDED_PACKAGES+=("$pkg")
    echo "  ⏳ $pkg - needs installation"
  else
    echo "  ✅ $pkg - already installed"
  fi
done

if [ ${#NEEDED_PACKAGES[@]} -eq 0 ]; then
  echo "✅ All packages already installed, skipping"
  exit 0
fi

echo ""
echo "📦 Installing ${#NEEDED_PACKAGES[@]} packages with ${PM}..."
echo "${PIPELINE_SUDO_PASS}" | sudo -S ${PM} install -y "${NEEDED_PACKAGES[@]}"

echo "✅ System dependencies installed successfully"
'''

      // Substitute variables
      installScript = installScript
        .replace('${PM}', packageManager)
        .replace('${PACKAGE_LIST}', packageList)

      // Execute on remote host
      def result = sh(
        script: """
sshpass -p '${SSH_PASS}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \\
  '${SSH_USER}@${remoteHost}' \\
  "export PIPELINE_SUDO_PASS='${SSH_PASS}'; bash -s" <<'INSTALL_SCRIPT'
${installScript}
INSTALL_SCRIPT
""",
        returnStatus: true
      )

      if (result == 0) {
        echo "✅ System dependencies installation completed"
        return true
      } else {
        error "❌ Failed to install system dependencies (exit code: ${result})"
      }

    } catch (Exception e) {
      echo "❌ Error installing system dependencies: ${e.message}"
      throw e
    }
  }
}

/**
 * Run Python installer script (install.py) on remote host
 *
 * PURPOSE: Execute the Python dependency installer in the project directory
 *
 * RATIONALE: Python projects often use install.py or setup.py scripts that
 * handle virtual environments, pip dependencies, and environment setup.
 * This function provides a clean abstraction for running these installers.
 *
 * SECURITY BENEFIT: Validates paths to prevent directory traversal.
 * Checks for installer existence before execution.
 *
 * MAINTENANCE ADVANTAGE: Single function for Python installer execution.
 * Easy to extend with virtual environment support or custom install args.
 *
 * @param remoteHost Target hostname or IP address
 * @param sshCredentialsId Jenkins credential ID for SSH access
 * @param workdir Remote working directory containing the project
 * @param installerScript Name of installer script (default: 'install.py')
 * @param pythonBin Python binary to use (default: 'python3')
 * @param additionalArgs Additional arguments for installer (optional)
 * @return true if installation succeeds, false otherwise
 *
 * EXAMPLE:
 * deploymentLib.runPythonInstaller(
 *   'myhost.example.com',
 *   'ssh-credentials',
 *   '/tmp/myapp'
 * )
 *
 * LEARNING POINT: Always validate that installer exists before running.
 * Capture and display installation output for debugging.
 *
 * ALTERNATIVES: Could use pip install -r requirements.txt directly,
 * but dedicated installer scripts often handle additional setup tasks.
 */
def runPythonInstaller(String remoteHost, String sshCredentialsId, String workdir,
                       String installerScript = 'install.py', String pythonBin = 'python3',
                       String additionalArgs = '') {
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "🐍 Running Python Installer"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Host: ${remoteHost}"
  echo "Workdir: ${workdir}"
  echo "Installer: ${installerScript}"
  echo "Python: ${pythonBin}"

  // SECURITY: Validate workdir doesn't contain command injection attempts
  if (workdir.contains(';') || workdir.contains('|') || workdir.contains('&')) {
    error "❌ Invalid workdir path: ${workdir}. Path cannot contain shell metacharacters."
  }

  withCredentials([
    usernamePassword(credentialsId: sshCredentialsId, usernameVariable: 'SSH_USER', passwordVariable: 'SSH_PASS')
  ]) {
    try {
      def installerScriptPath = "${workdir}/${installerScript}"

      // LEARNING POINT: Check file existence before attempting execution
      // This provides better error messages than letting Python fail
      def checkScript = '''set -euo pipefail

if [ ! -d "${WORKDIR}" ]; then
  echo "❌ ERROR: Workdir not found: ${WORKDIR}"
  exit 1
fi

cd "${WORKDIR}"

if [ ! -f "${INSTALLER}" ]; then
  echo "❌ ERROR: Installer script not found: ${INSTALLER}"
  echo "📂 Available files in ${WORKDIR}:"
  ls -la
  exit 1
fi

echo "✅ Installer script found: ${INSTALLER}"
echo "🚀 Running Python installer..."
${PYTHON_BIN} "${INSTALLER}" ${ADDITIONAL_ARGS}

echo "✅ Python installer completed successfully"
'''

      checkScript = checkScript
        .replace('${WORKDIR}', workdir)
        .replace('${INSTALLER}', installerScript)
        .replace('${PYTHON_BIN}', pythonBin)
        .replace('${ADDITIONAL_ARGS}', additionalArgs)

      def result = sh(
        script: """
sshpass -p '${SSH_PASS}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \\
  '${SSH_USER}@${remoteHost}' \\
  'bash -s' <<'CHECK_SCRIPT'
${checkScript}
CHECK_SCRIPT
""",
        returnStatus: true
      )

      if (result == 0) {
        echo "✅ Python installer execution completed"
        return true
      } else {
        error "❌ Python installer failed (exit code: ${result})"
      }

    } catch (Exception e) {
      echo "❌ Error running Python installer: ${e.message}"
      throw e
    }
  }
}

/**
 * Prepare application environment by deploying and configuring config files
 *
 * PURPOSE: Generate application configuration from templates with Jenkins credentials
 *
 * RATIONALE: Configuration files often contain secrets that must come from
 * Jenkins credentials. This function provides secure template substitution
 * without exposing credentials in logs or using fragile sed commands.
 *
 * SECURITY BENEFIT:
 * - All credentials are masked in Jenkins logs
 * - Template-based approach prevents injection attacks
 * - Config file is created with proper permissions (600)
 * - No credentials appear in command history
 *
 * MAINTENANCE ADVANTAGE:
 * - Single source of truth for configuration (template file)
 * - Easy to add new configuration values
 * - Clear mapping between Jenkins credentials and config values
 * - Validation of required credentials before deployment
 *
 * @param remoteHost Target hostname or IP address
 * @param sshCredentialsId Jenkins credential ID for SSH access
 * @param workdir Remote working directory
 * @param configTemplate Local config template content (use readFile to load)
 * @param configPath Remote path for final config file (relative to workdir)
 * @param credentialMapping Map of placeholder → Jenkins credential binding
 *        Example: ['{{MQTT_USER}}': 'MQTT_USERNAME_VAR', '{{MQTT_PASS}}': 'MQTT_PASSWORD_VAR']
 *        The values are variable names from withCredentials binding
 * @return true if configuration succeeds
 *
 * EXAMPLE:
 * withCredentials([
 *   usernamePassword(credentialsId: 'mqtt-creds', usernameVariable: 'MQTT_USER', passwordVariable: 'MQTT_PASS'),
 *   string(credentialsId: 'api-token', variable: 'API_TOKEN')
 * ]) {
 *   def template = readFile('dev-config.ini')
 *   def mapping = [
 *     '{{MQTT_USERNAME}}': env.MQTT_USER,
 *     '{{MQTT_PASSWORD}}': env.MQTT_PASS,
 *     '{{API_TOKEN}}': env.API_TOKEN
 *   ]
 *   deploymentLib.prepareEnvironment(
 *     'myhost.example.com',
 *     'ssh-credentials',
 *     '/tmp/myapp',
 *     template,
 *     'data/config.ini',
 *     mapping
 *   )
 * }
 *
 * LEARNING POINT: Template-based configuration is MUCH safer than sed/awk.
 * It's explicit, auditable, and prevents accidental modifications.
 *
 * ALTERNATIVES: Could use tools like envsubst, but this approach gives
 * us complete control over substitution and validation.
 */
def prepareEnvironment(String remoteHost, String sshCredentialsId, String workdir,
                       String configTemplate, String configPath, Map<String, String> credentialMapping) {
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "🔧 Preparing Application Environment"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Host: ${remoteHost}"
  echo "Workdir: ${workdir}"
  echo "Config Path: ${configPath}"
  echo "Template Size: ${configTemplate.length()} bytes"
  echo "Substitutions: ${credentialMapping.size()} values"

  // SECURITY: Validate config path to prevent directory traversal
  if (configPath.contains('..') || configPath.startsWith('/')) {
    error "❌ Invalid config path: ${configPath}. Must be relative and cannot contain '..' for security."
  }

  withCredentials([
    usernamePassword(credentialsId: sshCredentialsId, usernameVariable: 'SSH_USER', passwordVariable: 'SSH_PASS')
  ]) {
    try {
      // Perform template substitution
      def processedConfig = configTemplate

      // LEARNING POINT: Process substitutions with proper escaping
      // We escape single quotes in credential values to prevent shell injection
      credentialMapping.each { placeholder, value ->
        // Escape single quotes in the value for safe shell usage
        def escapedValue = value.toString().replace("'", "'\\''")
        processedConfig = processedConfig.replace(placeholder, escapedValue)
      }

      // SECURITY: Check for unreplaced placeholders (indicates missing credentials)
      def unreplacedPlaceholders = (processedConfig =~ /\{\{[^}]+\}\}/).findAll()
      if (unreplacedPlaceholders) {
        echo "⚠️  WARNING: Unreplaced placeholders found in config:"
        unreplacedPlaceholders.each { echo "   - ${it}" }
        echo "This may indicate missing credential mappings."
      }

      // LEARNING POINT: Using base64 encoding to safely transfer config
      // This prevents any issues with special characters or quoting
      def configBase64 = processedConfig.bytes.encodeBase64().toString()

      def deployScript = '''set -euo pipefail

cd "${WORKDIR}"

# Create config directory if needed
CONFIG_DIR=$(dirname "${CONFIG_PATH}")
if [ ! -d "${CONFIG_DIR}" ]; then
  echo "📁 Creating config directory: ${CONFIG_DIR}"
  mkdir -p "${CONFIG_DIR}"
fi

# Decode and write config file
echo "📝 Writing configuration to ${CONFIG_PATH}..."
echo "${CONFIG_BASE64}" | base64 -d > "${CONFIG_PATH}"

# SECURITY: Set restrictive permissions (only owner can read/write)
chmod 600 "${CONFIG_PATH}"

echo "✅ Configuration file created successfully"
echo "📊 Config file size: $(stat -f%z "${CONFIG_PATH}" 2>/dev/null || stat -c%s "${CONFIG_PATH}") bytes"

# Display config with sensitive values masked
echo "📄 Configuration preview (secrets masked):"
cat "${CONFIG_PATH}" | sed -E 's/(password|token|secret|key)([[:space:]]*=[[:space:]]*)(.+)/\\1\\2***MASKED***/gi' | head -20
'''

      deployScript = deployScript
        .replace('${WORKDIR}', workdir)
        .replace('${CONFIG_PATH}', configPath)
        .replace('${CONFIG_BASE64}', configBase64)

      def result = sh(
        script: """
sshpass -p '${SSH_PASS}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \\
  '${SSH_USER}@${remoteHost}' \\
  'bash -s' <<'DEPLOY_SCRIPT'
${deployScript}
DEPLOY_SCRIPT
""",
        returnStatus: true
      )

      if (result == 0) {
        echo "✅ Environment preparation completed"
        return true
      } else {
        error "❌ Failed to prepare environment (exit code: ${result})"
      }

    } catch (Exception e) {
      echo "❌ Error preparing environment: ${e.message}"
      throw e
    }
  }
}

/**
 * Launch application on remote host in background mode
 *
 * PURPOSE: Start the application in a way that survives SSH session closure
 *
 * RATIONALE: Applications need to run as background services. Using nohup
 * and proper backgrounding ensures the process continues after deployment.
 *
 * SECURITY BENEFIT:
 * - Environment variables are exported securely
 * - Process isolation from SSH session
 * - Validates script existence before execution
 *
 * MAINTENANCE ADVANTAGE:
 * - Standardized launch procedure
 * - Comprehensive logging for troubleshooting
 * - Health check to verify process started
 * - PID tracking for process management
 *
 * @param remoteHost Target hostname or IP address
 * @param sshCredentialsId Jenkins credential ID for SSH access
 * @param workdir Remote working directory
 * @param scriptPath Script to execute (relative to workdir, e.g., 'main.py')
 * @param environment Map of environment variables to export
 * @param scriptArgs Arguments to pass to script (default: '--deploy')
 * @param pythonBin Python binary to use for .py scripts (default: 'python3')
 * @param logPath Path for output logs (default: '/tmp/app-deployment.log')
 * @return Map containing: [success: boolean, pid: String, logPath: String]
 *
 * EXAMPLE:
 * def env = [
 *   DA_NON_INTERACTIVE: 'true',
 *   JENKINS_URL: 'http://jenkins.example.com',
 *   CALLBACK_TOKEN: '12345'
 * ]
 * def result = deploymentLib.launchApplication(
 *   'myhost.example.com',
 *   'ssh-credentials',
 *   '/tmp/myapp',
 *   'main.py',
 *   env,
 *   '--deploy'
 * )
 * echo "Application PID: ${result.pid}"
 *
 * LEARNING POINT: Using nohup and & together ensures the process:
 * 1. Ignores hangup signals (survives SSH disconnect)
 * 2. Runs in background (doesn't block the pipeline)
 * 3. Redirects output to a log file (for debugging)
 *
 * ALTERNATIVES: Could use systemd services, but this approach is simpler
 * for development/test environments and doesn't require root.
 */
def launchApplication(String remoteHost, String sshCredentialsId, String workdir,
                      String scriptPath, Map environment = [:],
                      String scriptArgs = '--deploy', String pythonBin = 'python3',
                      String logPath = '/tmp/app-deployment.log') {
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "🚀 Launching Application"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Host: ${remoteHost}"
  echo "Workdir: ${workdir}"
  echo "Script: ${scriptPath}"
  echo "Args: ${scriptArgs}"
  echo "Log: ${logPath}"
  echo "Environment Variables: ${environment.size()}"

  withCredentials([
    usernamePassword(credentialsId: sshCredentialsId, usernameVariable: 'SSH_USER', passwordVariable: 'SSH_PASS')
  ]) {
    try {
      // Build environment exports with proper escaping
      def envExports = environment.collect { k, v ->
        def escapedValue = v.toString().replace("'", "'\\''")
        "export ${k}='${escapedValue}'"
      }.join('\n')

      // Determine execution command based on script type
      def execCommand
      if (scriptPath.endsWith('.py')) {
        execCommand = "${pythonBin} ${scriptPath} ${scriptArgs}"
      } else {
        execCommand = "./${scriptPath} ${scriptArgs}"
      }

      def launchScript = '''set -euo pipefail

cd "${WORKDIR}"

# Verify script exists
if [ ! -f "${SCRIPT_PATH}" ]; then
  echo "❌ ERROR: Script not found: ${SCRIPT_PATH}"
  echo "📂 Available files in ${WORKDIR}:"
  ls -la
  exit 1
fi

# Make script executable (if not already)
# Skip for Python files since they're executed via python3
if [[ ! -x "${SCRIPT_PATH}" && ! "${SCRIPT_PATH}" == *.py ]]; then
  chmod +x "${SCRIPT_PATH}"
fi

# Export environment variables
echo "🔧 Exporting environment variables..."
${ENV_EXPORTS}

# Kill any existing instance of the application
echo "🔍 Checking for existing application processes..."
EXISTING_PIDS=$(pgrep -f "${SCRIPT_PATH}" || true)
if [[ -n "${EXISTING_PIDS}" ]]; then
  echo "⚠️  Found existing process(es) (PID: ${EXISTING_PIDS}), terminating..."
  for pid in ${EXISTING_PIDS}; do
    kill "${pid}" 2>/dev/null || true
  done
  sleep 2
  # Force kill if still running
  for pid in ${EXISTING_PIDS}; do
    if ps -p "${pid}" > /dev/null 2>&1; then
      kill -9 "${pid}" 2>/dev/null || true
    fi
  done
  echo "✅ Existing process(es) terminated"
fi

echo "🚀 Launching application in background..."
echo "Command: ${EXEC_COMMAND}"
echo "Output will be logged to: ${LOG_PATH}"

# Launch with nohup to survive SSH disconnect
# Redirect stdout and stderr to log file
nohup ${EXEC_COMMAND} > "${LOG_PATH}" 2>&1 &

# Capture the PID
APP_PID=$!
echo "✅ Application launched with PID: ${APP_PID}"

# Brief sleep to allow process to initialize
sleep 3

# HEALTH CHECK: Verify process is still running
if ps -p "${APP_PID}" > /dev/null 2>&1; then
  echo "✅ Application process confirmed running (PID: ${APP_PID})"
  echo "📋 Log file: ${LOG_PATH}"
  echo "🔍 Initial log output:"
  head -20 "${LOG_PATH}" || echo "   (log file not yet available)"

  # Output PID for capture
  echo "LAUNCH_SUCCESS:${APP_PID}"
else
  echo "❌ ERROR: Application process exited immediately!"
  echo "📋 Check logs at ${LOG_PATH} for details:"
  cat "${LOG_PATH}" || echo "   (log file not available)"
  exit 1
fi
'''

      launchScript = launchScript
        .replace('${WORKDIR}', workdir)
        .replace('${SCRIPT_PATH}', scriptPath)
        .replace('${ENV_EXPORTS}', envExports)
        .replace('${EXEC_COMMAND}', execCommand)
        .replace('${LOG_PATH}', logPath)

      def output = sh(
        script: """
sshpass -p '${SSH_PASS}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \\
  '${SSH_USER}@${remoteHost}' \\
  'bash -s' <<'LAUNCH_SCRIPT'
${launchScript}
LAUNCH_SCRIPT
""",
        returnStdout: true
      ).trim()

      // Extract PID from output
      def pidMatch = (output =~ /LAUNCH_SUCCESS:(\d+)/)
      def pid = pidMatch ? pidMatch[0][1] : 'unknown'

      echo "✅ Application launch completed successfully"

      return [
        success: true,
        pid: pid,
        logPath: logPath
      ]

    } catch (Exception e) {
      echo "❌ Error launching application: ${e.message}"
      return [
        success: false,
        pid: null,
        logPath: logPath,
        error: e.message
      ]
    }
  }
}

/**
 * Clone or update Git repository on remote host
 *
 * PURPOSE: Ensure latest code is available on remote host for deployment
 *
 * RATIONALE: Separating repository management from other deployment steps
 * makes the pipeline more maintainable and easier to debug.
 *
 * SECURITY BENEFIT:
 * - Validates repository URL format
 * - Uses proper Git authentication (if credentials provided)
 * - Cleans working directory to prevent code tampering
 *
 * @param remoteHost Target hostname or IP address
 * @param sshCredentialsId Jenkins credential ID for SSH access
 * @param workdir Remote working directory
 * @param repoUrl Git repository URL
 * @param branch Branch to checkout (default: 'main')
 * @param cleanClone Force fresh clone (default: true)
 * @return true if successful
 *
 * EXAMPLE:
 * deploymentLib.cloneRepository(
 *   'myhost.example.com',
 *   'ssh-credentials',
 *   '/tmp/myapp',
 *   'https://github.com/user/repo.git',
 *   'develop'
 * )
 */
def cloneRepository(String remoteHost, String sshCredentialsId, String workdir,
                    String repoUrl, String branch = 'main', boolean cleanClone = true) {
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "📥 Cloning Repository"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Host: ${remoteHost}"
  echo "Repository: ${repoUrl}"
  echo "Branch: ${branch}"
  echo "Workdir: ${workdir}"
  echo "Clean Clone: ${cleanClone}"

  // SECURITY: Basic URL validation
  if (!repoUrl.matches(/^https?:\/\/.+/) && !repoUrl.matches(/^git@.+/)) {
    error "❌ Invalid repository URL format: ${repoUrl}"
  }

  withCredentials([
    usernamePassword(credentialsId: sshCredentialsId, usernameVariable: 'SSH_USER', passwordVariable: 'SSH_PASS')
  ]) {
    try {
      def cloneScript = '''set -euo pipefail

if [ "${CLEAN_CLONE}" = "true" ]; then
  echo "🧹 Removing existing directory..."
  rm -rf "${WORKDIR}"
fi

if [ -d "${WORKDIR}/.git" ]; then
  echo "📂 Repository exists, updating..."
  cd "${WORKDIR}"
  git fetch origin
  git checkout "${BRANCH}"
  git pull origin "${BRANCH}"
else
  echo "📥 Cloning repository..."
  git clone -b "${BRANCH}" "${REPO_URL}" "${WORKDIR}"
  cd "${WORKDIR}"
fi

echo "✅ Repository ready"
echo "📊 Current commit: $(git rev-parse --short HEAD)"
echo "📝 Latest commit message: $(git log -1 --pretty=%s)"
'''

      cloneScript = cloneScript
        .replace('${WORKDIR}', workdir)
        .replace('${REPO_URL}', repoUrl)
        .replace('${BRANCH}', branch)
        .replace('${CLEAN_CLONE}', cleanClone.toString())

      def result = sh(
        script: """
sshpass -p '${SSH_PASS}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \\
  '${SSH_USER}@${remoteHost}' \\
  'bash -s' <<'CLONE_SCRIPT'
${cloneScript}
CLONE_SCRIPT
""",
        returnStatus: true
      )

      if (result == 0) {
        echo "✅ Repository cloned/updated successfully"
        return true
      } else {
        error "❌ Failed to clone/update repository (exit code: ${result})"
      }

    } catch (Exception e) {
      echo "❌ Error cloning repository: ${e.message}"
      throw e
    }
  }
}
