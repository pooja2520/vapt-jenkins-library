def call(Map config = [:]) {
    String serverUrl = config.vaptServerUrl ?: 'https://pt.maximusatlas.com'
    String apiKey = config.apiKey
    String target = config.target
    String branch = config.branch ?: env.BRANCH_NAME ?: 'unknown_branch'
    String pipelineRunId = config.pipelineRunId ?: env.BUILD_NUMBER ?: '000'
    String githubToken = config.githubToken ?: ''
    
    // Attempt to extract repo name from target URL if not explicitly provided
    String repoName = config.repoName
    if (!repoName && target) {
        def parts = target.split('/')
        repoName = parts.length >= 2 ? "${parts[-2]}/${parts[-1]}".replace('.git', '') : 'unknown_repo'
    } else if (!repoName) {
        repoName = 'unknown_repo'
    }

    if (!apiKey || !target) {
        error "maximusVaptScan requires 'apiKey' and 'target' parameters."
    }
    
    echo "Triggering Maximus VAPT Scan for ${target}..."
    
    String pythonScript = """
import sys, time, json, urllib.request, urllib.parse, urllib.error, os

server_url = sys.argv[1]
api_key = sys.argv[2]
target = sys.argv[3]
repo_name = sys.argv[4]
branch = sys.argv[5]
build_id = sys.argv[6]
token = sys.argv[7] if len(sys.argv) > 7 else ""

def req(url, data=None):
    req_obj = urllib.request.Request(url, headers={"X-API-Key": api_key})
    if data:
        req_obj.add_header("Content-Type", "application/json")
        data = json.dumps(data).encode("utf-8")
    return urllib.request.urlopen(req_obj, data=data)

try:
    print(f"Triggering scan at {server_url}/api/ci/code-scan/start")
    start_payload = {
        "source": target,
        "repo_name": repo_name,
        "branch": branch,
        "pipeline_run_id": build_id,
        "github_token": token
    }
    resp = req(f"{server_url}/api/ci/code-scan/start", data=start_payload)
    job_id = json.loads(resp.read())["job_id"]
    print(f"Started scan. Job ID: {job_id}")

    print("Polling for status...")
    while True:
        time.sleep(10)
        st = json.loads(req(f"{server_url}/api/ci/code-scan/status/{job_id}").read())
        status = st.get("status")
        print(f"Current Status: {status}")
        if status not in ["running", "pending"]:
            break

    if status in ["failed", "error"]:
        sys.exit("Scan failed on the server.")

    print("Scan completed successfully! Fetching scan results...")
    result_resp = req(f"{server_url}/api/ci/code-scan/result/{job_id}")
    results = json.loads(result_resp.read())
    
    summary = results.get("summary", {})
    critical = summary.get("critical", 0)
    high = summary.get("high", 0)
    
    print("\\n=== SCAN SUMMARY ===")
    print(f"Critical: {critical}")
    print(f"High:     {high}")
    print(f"Medium:   {summary.get('medium', 0)}")
    print(f"Low:      {summary.get('low', 0)}")
    print(f"Info:     {summary.get('info', 0)}")
    print("====================\\n")

    print("Downloading reports...")
    os.makedirs("vapt_reports", exist_ok=True)
    
    qs = urllib.parse.urlencode({"repo_name": repo_name, "branch": branch, "build_id": build_id})
    
    html_content = req(f"{server_url}/api/ci/code-scan/report/{job_id}/html?{qs}").read().decode("utf-8", errors="ignore")
    js_snippet = '''
    <script>
        document.addEventListener("DOMContentLoaded", function() {
            var tabs = document.querySelectorAll('.nav-link');
            tabs.forEach(function(tab) {
                tab.addEventListener('click', function(e) {
                    e.preventDefault();
                    tabs.forEach(function(t) { t.classList.remove('active'); });
                    this.classList.add('active');
                    var panes = document.querySelectorAll('.tab-pane');
                    panes.forEach(function(p) { p.classList.remove('show', 'active'); });
                    var targetId = this.getAttribute('href').substring(1);
                    var targetPane = document.getElementById(targetId);
                    if (targetPane) {
                        targetPane.classList.add('show', 'active');
                    }
                });
            });
        });
    </script>
    </body>
    '''
    html_content = html_content.replace("</body>", js_snippet)
    
    with open("vapt_reports/Maximus_VAPT_Report.html", "wb") as f:
        f.write(html_content.encode("utf-8"))
        
    with open("vapt_reports/Maximus_VAPT_Report.xlsx", "wb") as f:
        f.write(req(f"{server_url}/api/ci/code-scan/report/{job_id}/excel?{qs}").read())
        
    print("Reports downloaded successfully.")
    
    if critical > 0 or high > 0:
        sys.exit("Build Failed: Critical or High vulnerabilities found!")
    else:
        print("Build Passed: No critical/high vulnerabilities.")

except urllib.error.HTTPError as e:
    try:
        err_msg = json.loads(e.read().decode())["error"]
        sys.exit(f"API Error: {err_msg}")
    except:
        sys.exit(f"HTTP Error {e.code}: {e.reason}")
except Exception as e:
    sys.exit(f"Error executing VAPT scan: {str(e)}")
"""
    
    // Write the python script to workspace
    writeFile file: 'vapt_runner.py', text: pythonScript
    
    // Execute the python script securely without Groovy interpolation for secrets
    withEnv(["VAPT_API_KEY=${apiKey}", "GITHUB_TOKEN=${githubToken}"]) {
        sh "python3 vapt_runner.py '${serverUrl}' \"\$VAPT_API_KEY\" '${target}' '${repoName}' '${branch}' '${pipelineRunId}' \"\$GITHUB_TOKEN\""
    }
    
    // Archive reports
    archiveArtifacts artifacts: 'vapt_reports/*.*', allowEmptyArchive: true
    
    echo "Maximus VAPT Scan finished!"
}

