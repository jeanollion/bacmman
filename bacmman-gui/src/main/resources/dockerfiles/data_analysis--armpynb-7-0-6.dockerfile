FROM jupyter/scipy-notebook:aarch64-notebook-7.0.6
RUN pip install PyBacmman
RUN pip install matplotlib