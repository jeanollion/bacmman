FROM quay.io/jupyter/scipy-notebook:lab-4.3.6
#RUN pip install PyBacmman
RUN pip install git+https://git@github.com/jeanollion/PyBacmman.git

# Override settings: dark theme
RUN mkdir -p /opt/conda/share/jupyter/lab/settings
RUN echo '{"@jupyterlab/apputils-extension:themes": {"theme": "JupyterLab Dark"}}' > /opt/conda/share/jupyter/lab/settings/overrides.json