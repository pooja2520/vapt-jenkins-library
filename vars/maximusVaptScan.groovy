def call(Map config = [:]) {
    String serverUrl = config.vaptServerUrl ?: 'https://vapt.maximusatlas.com'
    String apiKey = config.apiKey
    String target = config.target
    
    if (!apiKey || !target) {
        error "maximusVaptScan requires 'apiKey' and 'target' parameters."
    }
    
    echo "Triggering Maximus VAPT Scan for ${target}..."
    
    String pythonScript = """
import sys, time, json, urllib.request, urllib.error, os

server_url = sys.argv[1]
api_key = sys.argv[2]
target = sys.argv[3]

def req(url, data=None):
    req_obj = urllib.request.Request(url, headers={"X-API-Key": api_key})
    if data:
        req_obj.add_header("Content-Type", "application/json")
        data = json.dumps(data).encode("utf-8")
    return urllib.request.urlopen(req_obj, data=data)

try:
    print(f"Triggering scan at {server_url}/api/ci/trigger")
    resp = req(f"{server_url}/api/ci/trigger", data={"target": target})
    task_id = json.loads(resp.read())["task_id"]
    print(f"Started scan. Task ID: {task_id}")

    print("Polling for status...")
    while True:
        time.sleep(10)
        st = json.loads(req(f"{server_url}/api/ci/status/{task_id}").read())
        status = st.get("status")
        print(f"Current Status: {status}")
        if status not in ["running", "pending"]:
            break

    if status == "failed":
        sys.exit("Scan failed on the server.")

    print("Scan completed successfully! Downloading reports...")
    os.makedirs("vapt_reports", exist_ok=True)
    
    with open("vapt_reports/Maximus_VAPT_Report.html", "wb") as f:
        f.write(req(f"{server_url}/api/ci/download_report/html?task_id={task_id}").read())
        
    with open("vapt_reports/Maximus_VAPT_Report.xlsx", "wb") as f:
        f.write(req(f"{server_url}/api/ci/download_report/excel?task_id={task_id}").read())
        
    print("Reports downloaded successfully.")
except Exception as e:
    sys.exit(f"Error executing VAPT scan: {str(e)}")
"""
    
    // Write the python script to workspace
    writeFile file: 'vapt_runner.py', text: pythonScript
    
    // Execute the python script
    sh "python3 vapt_runner.py '${serverUrl}' '${apiKey}' '${target}'"
    
    // Archive reports
    archiveArtifacts artifacts: 'vapt_reports/*.*', allowEmptyArchive: true
    
    echo "Maximus VAPT Scan finished!"
}
